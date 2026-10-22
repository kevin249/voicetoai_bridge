package io.github.kevin249.voicetoai

import java.util.Locale

/** Pure policy: Android adapters must never broaden these exact matches to substring clicks. */
object BridgePolicy {
    fun normalize(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    fun matches(value: String?, candidates: List<String>) =
        !value.isNullOrBlank() && candidates.any { normalize(it) == normalize(value) }

    fun validPackage(value: String) = value.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))

    fun selectUnique(idMatches: Set<Int>, labelMatches: Set<Int>): Int? {
        val candidates = idMatches.ifEmpty { labelMatches }
        return candidates.singleOrNull()
    }
}

/** Generation guard prevents a delayed load, timeout or click callback reviving a stopped session. */
class SessionGate {
    var generation = 0L
        private set
    fun next(): Long = ++generation
    fun accepts(token: Long) = token == generation
}

enum class Phase { STOPPED, STARTING, LISTENING, HANDOFF, PAUSED, ERROR }

data class WakePhrase(val label: String, val tokens: String) {
    companion object {
        val presets = listOf(
            WakePhrase("召唤大模型", "zh ào h uàn d à m ó x íng"),
            WakePhrase("你好助手", "n ǐ h ǎo zh ù sh ǒu"),
            WakePhrase("小智小智", "x iǎo zh ì x iǎo zh ì")
        )
    }
}
