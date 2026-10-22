package io.github.kevin249.voicetoai

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Only a bounded, user-triggered request may inspect or click a configured client window. */
class VoiceAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Request? = null
    private data class Request(val profile: ClientProfile, val learn: Boolean, val deadline: Long,
        val callback: (Boolean, String) -> Unit)
    private val poll = object : Runnable {
        override fun run() {
            val request = pending ?: return
            if (SystemClock.uptimeMillis() >= request.deadline) {
                finish(false, if (request.learn) "学习超时：未取得可识别的按钮，请手动配置" else "未找到唯一语音按钮：请先登录客户端，再学习按钮或修改规则")
                return
            }
            if (!unlocked()) { finish(false, "请亮屏并解锁后重试"); return }
            if (!request.learn) attemptClick(request)
            if (pending === request) handler.postDelayed(this, 300)
        }
    }

    override fun onServiceConnected() {
        instance = this
        // No idle observation: only the bridge itself is subscribed until a request is armed.
        restrictTo(setOf(packageName))
    }

    fun openVoice(profile: ClientProfile, learn: Boolean = false, callback: (Boolean, String) -> Unit) {
        cancel()
        try {
            if (learn) {
                require(BridgePolicy.validPackage(profile.launchPackage) && profile.windowPackages.isNotEmpty() &&
                    profile.windowPackages.all(BridgePolicy::validPackage)) { "请先配置启动包名和界面包名" }
            } else profile.validate()
            require(unlocked()) { "请亮屏并解锁后重试" }
            val intent = packageManager.getLaunchIntentForPackage(profile.launchPackage)
                ?: error("未找到 ${profile.name}：请安装官方应用并检查启动包名")
            val request = Request(profile, learn, SystemClock.uptimeMillis() + if (learn) 30_000 else 12_000, callback)
            pending = request
            restrictTo(profile.windowPackages)
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (learn) BridgeStatus.update(Phase.HANDOFF, "请在 30 秒内亲手点击一次客户端的语音 / Live 按钮")
            handler.postDelayed(poll, 600)
        } catch (e: RuntimeException) {
            cancel()
            callback(false, e.message ?: "无法打开客户端")
        }
    }

    private fun unlocked() = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun restrictTo(packages: Set<String>) {
        serviceInfo = serviceInfo.apply { packageNames = packages.toTypedArray() }
    }

    @Suppress("DEPRECATION")
    private fun attemptClick(request: Request) {
        val root = rootInActiveWindow ?: return
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            if (root.packageName?.toString() !in request.profile.windowPackages) return
            collect(root, nodes)
            val visible = nodes.filter { it.isVisibleToUser && it.isEnabled && !it.isPassword && !it.isEditable }
            if (visible.any { BridgePolicy.matches(it.text?.toString(), request.profile.activeLabels) ||
                    BridgePolicy.matches(it.contentDescription?.toString(), request.profile.activeLabels) }) {
                finish(true, "检测到已在语音界面，未重复点击；监听已暂停")
                return
            }
            val idMatches = linkedSetOf<Int>()
            val labelMatches = linkedSetOf<Int>()
            val targets = mutableListOf<AccessibilityNodeInfo>()
            fun add(node: AccessibilityNodeInfo, set: MutableSet<Int>) {
                val target = clickableParent(node) ?: return
                val old = targets.indexOfFirst { it == target }
                if (old >= 0) { target.recycle(); set += old }
                else { targets += target; set += targets.lastIndex }
            }
            try {
                visible.forEach { node ->
                    if (node.packageName?.toString() in request.profile.windowPackages) {
                        if (node.viewIdResourceName in request.profile.viewIds) add(node, idMatches)
                        if (BridgePolicy.matches(node.text?.toString(), request.profile.labels) ||
                            BridgePolicy.matches(node.contentDescription?.toString(), request.profile.labels)) add(node, labelMatches)
                    }
                }
                val selected = BridgePolicy.selectUnique(idMatches, labelMatches)
                if (selected == null) {
                    if ((if (idMatches.isNotEmpty()) idMatches else labelMatches).size > 1)
                        finish(false, "匹配到多个按钮，已停止；请学习按钮或填写唯一 ID")
                    return
                }
                // Mark request complete BEFORE action dispatch; subsequent UI events cannot click twice.
                val target = targets[selected]
                if (target.packageName?.toString() !in request.profile.windowPackages) return
                cancel()
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                request.callback(clicked, if (clicked) "已点击 ${request.profile.name} 语音按钮，监听已暂停；请确认客户端开始聆听后再说话"
                    else "按钮拒绝点击，已停止；请手动进入语音并重新学习按钮")
            } finally { targets.forEach { it.recycle() } }
        } catch (_: RuntimeException) {
            finish(false, "界面已变化，已停止点击；请重新测试")
        } finally {
            nodes.forEach { it.recycle() }
            root.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun collect(root: AccessibilityNodeInfo, output: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (output.size >= 400 || depth > 30) return
        output += AccessibilityNodeInfo.obtain(root)
        for (i in 0 until root.childCount) {
            if (output.size >= 400) break
            val child = root.getChild(i) ?: continue
            try { collect(child, output, depth + 1) } finally { child.recycle() }
        }
    }

    @Suppress("DEPRECATION")
    private fun clickableParent(source: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(source)
        repeat(4) {
            val current = node ?: return null
            if (current.isPassword || current.isEditable || !current.isVisibleToUser || !current.isEnabled) {
                current.recycle(); return null
            }
            if (current.isClickable) return current
            node = current.parent
            current.recycle()
        }
        node?.recycle()
        return null
    }

    @Suppress("DEPRECATION")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val request = pending ?: return
        if (!request.learn || event?.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        if (SystemClock.uptimeMillis() >= request.deadline || !unlocked()) {
            finish(false, "学习已过期或设备已锁定，请重新开始"); return
        }
        if (event.packageName?.toString() !in request.profile.windowPackages) return
        val root = rootInActiveWindow ?: return
        val source = event.source
        try {
            if (root.packageName?.toString() !in request.profile.windowPackages || source == null) return
            if (source.isPassword || source.isEditable || source.packageName?.toString() !in request.profile.windowPackages) return
            val label = source.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: source.text?.toString()?.takeIf { it.isNotBlank() }
            val id = source.viewIdResourceName?.takeIf { candidate -> request.profile.windowPackages.any { candidate.startsWith("$it:id/") } }
            if (id == null && (label.isNullOrBlank() || label.length > 100)) {
                finish(false, "此控件未提供可用 ID 或标签，请手动配置"); return
            }
            val updated = request.profile.copy(viewIds = listOfNotNull(id), labels = listOfNotNull(label?.takeIf { it.length <= 100 }))
            try {
                Settings(this).save(updated)
                finish(true, "已学习刚才点击的按钮；结束客户端语音后返回本应用测试")
            } catch (e: IllegalArgumentException) { finish(false, e.message ?: "无法保存此按钮") }
        } finally { source?.recycle(); root.recycle() }
    }

    private fun finish(success: Boolean, message: String) {
        val request = pending ?: return
        cancel()
        request.callback(success, message)
    }
    fun cancel() {
        pending = null
        handler.removeCallbacks(poll)
        if (instance === this) restrictTo(setOf(packageName))
    }
    override fun onInterrupt() { finish(false, "无障碍操作被中断，请重试") }
    override fun onDestroy() {
        finish(false, "无障碍服务已断开，请重新启用")
        cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }
    companion object { var instance: VoiceAccessibilityService? = null; private set }
}
