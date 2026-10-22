package io.github.kevin249.voicetoai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.*
import android.widget.*

class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var status: TextView
    private lateinit var permissions: TextView
    private lateinit var clients: Spinner
    private var pendingAction: String? = null
    private val listener: () -> Unit = { renderStatus() }
    private val ink = Color.rgb(29, 48, 43)
    private val muted = Color.rgb(96, 113, 106)
    private val green = Color.rgb(23, 107, 88)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(244, 246, 243)); isFillViewport = true }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(32))
        }
        scroll.addView(page)
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        page.addView(text("VOICE TO AI  /  0.1", 12, green, true))
        page.addView(text("说一句，开始对话。", 28, ink, true).apply { setPadding(0, dp(10), 0, dp(8)) })
        page.addView(text("离线唤醒 · 官方客户端 · 无需模型 API", 14, muted))

        val stateCard = card(page)
        stateCard.addView(text("当前状态", 13, muted))
        status = text("", 18, ink, true).apply { setPadding(0, dp(10), 0, dp(10)) }
        stateCard.addView(status)
        stateCard.addView(text("唤醒后稍等，看到客户端开始聆听再说问题。", 13, muted))

        val config = card(page)
        config.addView(text("01  选择唤醒词与客户端", 16, ink, true))
        val phrases = Spinner(this)
        phrases.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, WakePhrase.presets.map { it.label })
        phrases.setSelection(settings.phraseIndex)
        phrases.onItemSelectedListener = selected { index ->
            if (settings.phraseIndex != index) { settings.phraseIndex = index; stopForConfigChange() }
        }
        config.addView(phrases, rowParams())
        clients = Spinner(this)
        refreshClients()
        clients.onItemSelectedListener = selected { index ->
            val id = settings.profiles()[index].id
            if (settings.selectedId != id) { settings.selectedId = id; stopForConfigChange() }
        }
        config.addView(clients, rowParams())
        config.addView(button("编辑客户端 / 按钮规则", false) { editProfile() })
        config.addView(text("ChatGPT 与 Gemini 的预设标签需要在你的 APK 版本上验证。也可配置豆包、通义等客户端。", 13, muted))

        val access = card(page)
        access.addView(text("02  授权并校准按钮", 16, ink, true))
        permissions = text("", 13, muted).apply { setPadding(0, dp(10), 0, dp(6)) }
        access.addView(permissions)
        access.addView(button("启用无障碍服务", false) {
            disclose { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        access.addView(button("学习语音按钮（手动点一次）", false) {
            AlertDialog.Builder(this).setTitle("学习当前客户端的语音按钮")
                .setMessage("将打开所选客户端。请在 30 秒内亲手点击语音 / Live 按钮。仅保存该按钮提供的 ID 或标签。请先登录并关闭其他弹窗。")
                .setPositiveButton("开始学习") { _, _ -> requestAction(WakeService.LEARN) }
                .setNegativeButton("取消", null).show()
        })
        access.addView(button("测试：打开客户端并点击语音", false) { requestAction(WakeService.TEST) })

        val controls = card(page)
        controls.addView(text("03  开始使用", 16, ink, true))
        controls.addView(button("开始监听唤醒词", true) { requestAction(WakeService.START) })
        controls.addView(button("已结束 AI 对话，恢复监听", false) { requestAction(WakeService.START) })
        controls.addView(button("停止监听与待执行点击", false) {
            stopService(Intent(this, WakeService::class.java))
            VoiceAccessibilityService.instance?.cancel()
            BridgeStatus.update(Phase.STOPPED, "已停止监听和待执行点击")
        })
        controls.addView(Switch(this).apply {
            text = "保持本页面亮屏（专用终端）"
            setTextColor(ink)
            setOnCheckedChangeListener { _, checked ->
                if (checked) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        })
        controls.addView(text("进入 AI 对话后，桥接会释放麦克风并暂停。对话结束后，点通知回到这里恢复监听。锁屏状态不会自动解锁。", 13, muted).apply { setPadding(0, dp(12), 0, 0) })
        page.addView(text("本地处理，不保存录音或对话。自动操作时临时检查目标界面以定位按钮。此应用不含联网权限；AI 客户端按其自身规则使用网络。", 12, muted).apply { setPadding(0, dp(18), 0, 0) })
    }

    override fun onResume() {
        super.onResume()
        BridgeStatus.add(listener)
        renderStatus()
    }
    override fun onPause() { BridgeStatus.remove(listener); super.onPause() }

    private fun renderStatus() {
        if (!::status.isInitialized) return
        val title = when (BridgeStatus.phase) {
            Phase.STOPPED -> "待机"
            Phase.STARTING -> "准备中"
            Phase.LISTENING -> "聆听唤醒词"
            Phase.HANDOFF -> "交接给客户端"
            Phase.PAUSED -> "监听已暂停"
            Phase.ERROR -> "需要处理"
        }
        status.text = "$title\n${BridgeStatus.message}"
        status.setTextColor(if (BridgeStatus.phase == Phase.ERROR) Color.rgb(154, 71, 37) else green)
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        permissions.text = "麦克风：${if (mic) "已授权" else "开始时申请"}    无障碍：${if (VoiceAccessibilityService.instance != null) "已连接" else "未连接"}"
    }

    private fun disclose(action: () -> Unit) {
        if (settings.disclosureAccepted) { action(); return }
        AlertDialog.Builder(this).setTitle("开启本地语音桥接")
            .setMessage("监听期间，麦克风音频只在本机用于检测唤醒词，不保存、不上传。\n\n无障碍服务仅在你唤醒或测试时临时读取所配置 AI 应用的无障碍界面，用于定位按钮并执行一次点击，不保存界面或对话。学习时记录你亲手点击的控件 ID / 标签。它不负责自动登录、授权或发送文字。你可随时停止或在系统设置关闭服务。")
            .setPositiveButton("同意并继续") { _, _ -> settings.disclosureAccepted = true; action() }
            .setNegativeButton("取消", null).show()
    }

    private fun requestAction(action: String) = disclose {
        if (VoiceAccessibilityService.instance == null) {
            toast("请先启用“语音桥接”无障碍服务")
            return@disclose
        }
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) {
            pendingAction = action
            requestPermissions(missing.toTypedArray(), 10)
        } else execute(action)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = pendingAction
        pendingAction = null
        if (requestCode == 10 && action != null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) execute(action)
        else toast("需要麦克风权限才能监听；请在系统应用权限中开启")
    }
    private fun execute(action: String) {
        try { startForegroundService(Intent(this, WakeService::class.java).setAction(action)) }
        catch (e: RuntimeException) { BridgeStatus.update(Phase.ERROR, "无法启动：${e.message}") }
    }
    private fun stopForConfigChange() {
        stopService(Intent(this, WakeService::class.java))
        VoiceAccessibilityService.instance?.cancel()
        BridgeStatus.update(Phase.STOPPED, "配置已修改，请重新开始监听")
    }

    private fun editProfile() {
        val profile = settings.selected()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun field(label: String, value: String, multi: Boolean = false): EditText {
            form.addView(text(label, 13, muted).apply { setPadding(0, dp(12), 0, 0) })
            return EditText(this).apply {
                setText(value); textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT or if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                setSingleLine(!multi)
                form.addView(this)
            }
        }
        val name = field("显示名称", profile.name)
        val pkg = field("启动包名", profile.launchPackage)
        val windows = field("允许操作的界面包名（每行一个）", profile.windowPackages.joinToString("\n"), true)
        val ids = field("按钮完整 ID（可留空，每行一个）", profile.viewIds.joinToString("\n"), true)
        val labels = field("语音按钮完整标签（每行一个，可先留空后学习）", profile.labels.joinToString("\n"), true)
        val active = field("已进入语音的控件标签（防止重复点击）", profile.activeLabels.joinToString("\n"), true)
        form.addView(text("Gemini 常由 Google 应用承载界面，启动包名与界面包名可能不同。规则只做完整匹配，不支持正则或坐标。", 12, muted))
        val dialog = AlertDialog.Builder(this).setTitle("${profile.name} · 适配规则")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("保存", null).setNegativeButton("取消", null)
            .setNeutralButton("恢复预设") { _, _ -> settings.reset(profile.id); stopForConfigChange(); refreshClients() }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun lines(view: EditText) = view.text.toString().lines().map(String::trim).filter(String::isNotBlank).distinct()
                try {
                    settings.save(profile.copy(name = name.text.toString().trim(), launchPackage = pkg.text.toString().trim(),
                        windowPackages = lines(windows).toSet(), viewIds = lines(ids), labels = lines(labels), activeLabels = lines(active)))
                    stopForConfigChange(); refreshClients(); dialog.dismiss()
                } catch (e: IllegalArgumentException) { toast(e.message ?: "配置错误") }
            }
        }
        dialog.show()
    }

    private fun refreshClients() {
        val all = settings.profiles()
        clients.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, all.map { it.name })
        clients.setSelection(all.indexOfFirst { it.id == settings.selectedId }.coerceAtLeast(0))
    }
    private fun selected(action: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun card(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    }
    private fun button(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; minHeight = dp(52)
        backgroundTintList = ColorStateList.valueOf(if (primary) green else Color.rgb(232, 240, 235))
        setTextColor(if (primary) Color.WHITE else ink)
        layoutParams = rowParams(); setOnClickListener { action() }
    }
    private fun rowParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
