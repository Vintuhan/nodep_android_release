package com.nodep.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Как у конкурентов (BetBlocker / OFFBET / Bad Gambler):
 * Accessibility — второй слой, не занимает слот VPN.
 * Можно держать обычный VPN для интернета в РФ.
 */
class NodepAccessibilityService : AccessibilityService() {

    private var lastBlockAt = 0L
    private var lastKey = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Blocklist.load(this)
        isEnabled = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!isEnabled) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg.startsWith("com.android.systemui")) return

        // 1) известное приложение казино/БК
        if (Blocklist.isBlockedPackage(pkg)) {
            showBlock("приложение: $pkg")
            return
        }

        // 2) текст окна / URL в браузере
        val root = rootInActiveWindow ?: return
        val blob = StringBuilder()
        collectText(root, blob, 0)
        root.recycle()
        val text = blob.toString()
        if (text.length < 4) return

        if (Blocklist.isBlockedText(text)) {
            showBlock(text.take(80))
        }
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (depth > 12) return
        node.text?.let { if (it.isNotEmpty()) out.append(it).append(' ') }
        node.contentDescription?.let { if (it.isNotEmpty()) out.append(it).append(' ') }
        node.viewIdResourceName?.let { if (it.contains("url") || it.contains("address")) out.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collectText(c, out, depth + 1)
            c.recycle()
        }
    }

    private fun showBlock(detail: String) {
        val now = System.currentTimeMillis()
        val key = detail.take(40)
        if (key == lastKey && now - lastBlockAt < 2500) return
        lastKey = key
        lastBlockAt = now

        val i = Intent(this, BlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(BlockActivity.EXTRA_DETAIL, detail)
        }
        startActivity(i)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        isEnabled = false
        super.onDestroy()
    }

    companion object {
        @Volatile var isEnabled: Boolean = false
    }
}
