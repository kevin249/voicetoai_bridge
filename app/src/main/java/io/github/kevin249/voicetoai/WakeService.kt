package io.github.kevin249.voicetoai

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WakeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val gate = SessionGate()
    private var cancelled = AtomicBoolean(false)
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "语音唤醒", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopBridge(); return START_NOT_STICKY }
        if (intent?.action != START && intent?.action != TEST && intent?.action != LEARN) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!Settings(this).disclosureAccepted || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            BridgeStatus.update(Phase.ERROR, "请先在应用内授权麦克风并确认用途")
            stopSelf(); return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification("正在准备"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification("正在准备"))
            foreground = true
            if (intent.action == START) startListening() else testClient(intent.action == LEARN)
        } catch (e: RuntimeException) {
            BridgeStatus.update(Phase.ERROR, "启动失败：${e.message ?: "请回到应用内重新启动"}")
            stopSelf()
        }
        return START_NOT_STICKY // Never silently restart microphone recording after process death.
    }

    private fun cancelCurrent(): Long {
        cancelled.set(true)
        VoiceAccessibilityService.instance?.cancel()
        return gate.next()
    }

    private fun startListening() {
        val token = cancelCurrent()
        val bridge = VoiceAccessibilityService.instance
        if (bridge == null) { set(Phase.ERROR, "请先启用“语音桥接”无障碍服务"); return }
        val settings = Settings(this)
        val profile = settings.selected()
        try { profile.validate() } catch (e: IllegalArgumentException) { set(Phase.ERROR, e.message ?: "配置错误"); return }
        val phrase = WakePhrase.presets[settings.phraseIndex]
        val signal = AtomicBoolean(false)
        cancelled = signal
        set(Phase.STARTING, "正在载入本地唤醒模型…")
        worker.execute {
            try {
                if (signal.get()) return@execute
                // Refuse to steal audio from an existing recording session when the OS exposes it.
                val audio = getSystemService(AudioManager::class.java)
                check(audio.activeRecordingConfigurations.isEmpty()) { "麦克风正在使用，请先结束 AI 语音或其他录音" }
                val detected = SherpaWakeEngine(applicationContext).listen(phrase, signal) {
                    handler.post { if (gate.accepts(token)) set(Phase.LISTENING, "正在监听：“${phrase.label}” → ${profile.name}") }
                }
                handler.post {
                    if (gate.accepts(token) && detected && !signal.get()) launchClient(profile, token)
                }
            } catch (e: Exception) {
                handler.post { if (gate.accepts(token)) set(Phase.ERROR, "监听失败：${e.message ?: "未知错误"}") }
            } catch (e: LinkageError) {
                handler.post { if (gate.accepts(token)) set(Phase.ERROR, "本机不支持此语音引擎：${e.javaClass.simpleName}") }
            }
        }
    }

    private fun testClient(learn: Boolean) {
        val token = cancelCurrent()
        val profile = Settings(this).selected()
        set(Phase.HANDOFF, "正在释放麦克风…")
        // Queued after listen(): even rapid stop/test/resume cannot race AudioRecord.release().
        worker.execute { handler.post { if (gate.accepts(token)) launchClient(profile, token, learn) } }
    }

    private fun launchClient(profile: ClientProfile, token: Long, learn: Boolean = false) {
        val bridge = VoiceAccessibilityService.instance
        if (bridge == null) { set(Phase.ERROR, "无障碍服务未连接，请回到应用启用"); return }
        set(Phase.HANDOFF, "已释放麦克风，正在打开 ${profile.name}…")
        bridge.openVoice(profile, learn) { success, detail ->
            if (gate.accepts(token)) set(if (success) Phase.PAUSED else Phase.ERROR, detail)
        }
    }

    private fun set(phase: Phase, message: String) {
        BridgeStatus.update(phase, message)
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message))
    }
    private fun stopBridge() {
        cancelCurrent()
        BridgeStatus.update(Phase.STOPPED, "已停止监听和待执行点击")
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_voice)
            .setContentTitle("语音桥接").setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "打开 / 恢复监听", open).build())
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }
    override fun onDestroy() {
        cancelCurrent()
        worker.shutdown() // Existing worker exits cooperatively and releases microphone in finally.
        foreground = false
        if (BridgeStatus.phase != Phase.ERROR) BridgeStatus.update(Phase.STOPPED, "监听已停止")
        super.onDestroy()
    }
    companion object {
        const val START = "bridge.START"
        const val TEST = "bridge.TEST"
        const val LEARN = "bridge.LEARN"
        const val STOP = "bridge.STOP"
        private const val CHANNEL = "voice_bridge"
        private const val NOTIFICATION = 1
    }
}
