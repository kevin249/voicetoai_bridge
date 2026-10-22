package io.github.kevin249.voicetoai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ClientProfile(
    val id: String,
    val name: String,
    val launchPackage: String,
    val windowPackages: Set<String>,
    val viewIds: List<String>,
    val labels: List<String>,
    val activeLabels: List<String>
) {
    fun validate(requireButton: Boolean = true) {
        require(name.isNotBlank()) { "请填写客户端名称" }
        require(BridgePolicy.validPackage(launchPackage)) { "启动包名格式不正确" }
        require(windowPackages.isNotEmpty() && windowPackages.all(BridgePolicy::validPackage)) { "请填写允许操作的界面包名" }
        require(!requireButton || viewIds.isNotEmpty() || labels.isNotEmpty()) { "请先学习按钮，或填写按钮 ID / 完整标签" }
        require((labels + activeLabels).none { it.isBlank() || it.length > 100 }) { "按钮标签不能为空或超过 100 字符" }
        require(viewIds.all { id -> windowPackages.any { id.startsWith("$it:id/") } }) { "按钮 ID 必须属于允许操作的包名，格式为 包名:id/名称" }
        require(labels.none { BridgePolicy.matches(it, activeLabels) }) { "启动按钮与通话中标记不能相同" }
    }
    companion object {
        // Candidate labels only. Client UI versions must be calibrated on the user's device.
        val defaults = listOf(
            ClientProfile("chatgpt", "ChatGPT", "com.openai.chatgpt", setOf("com.openai.chatgpt"), emptyList(),
                listOf("Start voice mode", "Voice mode", "Start voice conversation", "开始语音模式", "语音模式", "开始语音对话"),
                listOf("End voice conversation", "End conversation", "结束语音对话", "结束对话")),
            ClientProfile("gemini", "Gemini Live", "com.google.android.apps.bard",
                setOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox"), emptyList(),
                listOf("Go Live", "Start Live", "开始 Live", "开始 Live 对话"),
                listOf("End Live", "End Live conversation", "结束 Live 对话")),
            ClientProfile("custom", "自定义客户端", "", emptySet(), emptyList(), emptyList(), emptyList())
        )
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    var selectedId: String
        get() = prefs.getString("selected", "chatgpt")!!
        set(value) { prefs.edit().putString("selected", value).apply() }
    var phraseIndex: Int
        get() = prefs.getInt("phrase", 0).coerceIn(WakePhrase.presets.indices)
        set(value) { prefs.edit().putInt("phrase", value).apply() }
    var disclosureAccepted: Boolean
        get() = prefs.getBoolean("disclosure", false)
        set(value) { prefs.edit().putBoolean("disclosure", value).apply() }

    fun profiles() = ClientProfile.defaults.map { default ->
        runCatching {
            val obj = JSONObject(prefs.getString("profile.${default.id}", null) ?: return@map default)
            fun list(key: String): List<String> {
                val a = obj.getJSONArray(key)
                return (0 until a.length()).map { a.getString(it) }
            }
            ClientProfile(default.id, obj.getString("name"), obj.getString("package"),
                list("windows").toSet(), list("ids"), list("labels"), list("active"))
        }.getOrDefault(default)
    }
    fun selected() = profiles().firstOrNull { it.id == selectedId } ?: profiles().first()
    fun save(profile: ClientProfile) {
        profile.validate(requireButton = false)
        val obj = JSONObject().put("name", profile.name).put("package", profile.launchPackage)
            .put("windows", JSONArray(profile.windowPackages.toList())).put("ids", JSONArray(profile.viewIds))
            .put("labels", JSONArray(profile.labels)).put("active", JSONArray(profile.activeLabels))
        prefs.edit().putString("profile.${profile.id}", obj.toString()).apply()
    }
    fun reset(id: String) { prefs.edit().remove("profile.$id").apply() }
}
