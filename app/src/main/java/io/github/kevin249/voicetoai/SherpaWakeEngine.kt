package io.github.kevin249.voicetoai

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import com.k2fsa.sherpa.onnx.*
import java.util.concurrent.atomic.AtomicBoolean

/** All native handles and AudioRecord belong to ONE worker. Returning guarantees mic release. */
class SherpaWakeEngine(private val context: Context) {
    @SuppressLint("MissingPermission", "WakelockTimeout")
    fun listen(phrase: WakePhrase, cancelled: AtomicBoolean, onReady: () -> Unit): Boolean {
        var spotter: KeywordSpotter? = null
        var stream: OnlineStream? = null
        var recorder: AudioRecord? = null
        val lock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicetoai:keyword")
        try {
            lock.acquire() // User-controlled foreground service; always released in finally.
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig("kws/encoder.int8.onnx", "kws/decoder.onnx", "kws/joiner.int8.onnx"),
                    tokens = "kws/tokens.txt", numThreads = 2, provider = "cpu", modelType = "zipformer2"
                ),
                keywordsFile = "kws/keywords-${WakePhrase.presets.indexOf(phrase)}.txt", keywordsScore = 1.5f, keywordsThreshold = 0.25f,
                numTrailingBlanks = 2
            )
            spotter = KeywordSpotter(context.assets, config)
            if (cancelled.get()) return false
            stream = spotter.createStream()
            check(stream.ptr != 0L) { "唤醒词与模型不兼容" }
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "设备不支持 16 kHz 单声道录音" }
            if (cancelled.get()) return false
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音，请检查权限与麦克风占用" }
            onReady()
            val buffer = ShortArray(1600)
            while (!cancelled.get()) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                check(count > 0) { "录音中断（$count），请重新开始监听" }
                if (cancelled.get()) return false
                stream.acceptWaveform(FloatArray(count) { buffer[it] / 32768f }, 16000)
                while (!cancelled.get() && spotter.isReady(stream)) {
                    spotter.decode(stream)
                    if (spotter.getResult(stream).keyword.isNotBlank()) return true
                }
            }
            return false
        } finally {
            // Order matters: release audio BEFORE reporting detection to the client launcher.
            recorder?.let { runCatching { it.stop() }; it.release() }
            stream?.release()
            spotter?.release()
            if (lock.isHeld) lock.release()
        }
    }
}
