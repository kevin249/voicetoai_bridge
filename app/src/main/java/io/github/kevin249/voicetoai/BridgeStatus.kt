package io.github.kevin249.voicetoai

/** Main-thread-only, memory-only status. No audio or client screen text is logged. */
object BridgeStatus {
    var phase = Phase.STOPPED
        private set
    var message = "尚未监听"
        private set
    private val listeners = mutableSetOf<() -> Unit>()
    fun update(next: Phase, detail: String) {
        phase = next
        message = detail
        listeners.toList().forEach { it() }
    }
    fun add(listener: () -> Unit) { listeners += listener }
    fun remove(listener: () -> Unit) { listeners -= listener }
}
