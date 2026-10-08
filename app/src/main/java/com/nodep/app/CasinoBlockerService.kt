package com.nodep.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Служба спец. возможностей — основной способ блокировки на Android.
 * Не занимает слот VPN: обычный VPN для интернета можно оставить.
 *
 * 1) Пакеты приложений из чёрного списка
 * 2) Рекурсивный обход экрана → URL / текст с игорными маркерами
 * При срабатывании открываем MainActivity с blocked.html
 */
class CasinoBlockerService : AccessibilityService() {

    private var lastBlockAt = 0L
    private var lastKey = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Blocklist.load(this)
        isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isRunning) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val pkg = event.packageName?.toString() ?: return
        // не блокируем сами себя и системный UI
        if (pkg == packageName) return
        if (pkg.startsWith("com.android.systemui") ||
            pkg == "com.android.settings" ||
            pkg.startsWith("com.google.android.inputmethod")
        ) return

        // --- 1. Известное приложение казино / БК ---
        if (Blocklist.isBlockedPackage(pkg)) {
            triggerBlock("app:$pkg")
            return
        }

        // --- 2. Скан дерева экрана (URL адресной строки и текст) ---
        val root = rootInActiveWindow ?: return
        try {
            val found = scanForGambling(root)
            if (found != null) triggerBlock(found)
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * Рекурсивный обход AccessibilityNodeInfo.
     * Ищем URL (http/https/www) и бренды/слова казино.
     */
    private fun scanForGambling(node: AccessibilityNodeInfo): String? {
        val chunks = ArrayList<String>()
        collect(node, chunks, 0)
        for (chunk in chunks) {
            val hit = Blocklist.matchText(chunk)
            if (hit != null) return hit
        }
        return null
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 14) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        // viewId часто содержит url_bar / address
        node.viewIdResourceName?.let { id ->
            if (id.contains("url", true) || id.contains("address", true) || id.contains("omnibox", true)) {
                node.text?.toString()?.let { out.add(it) }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collect(child, out, depth + 1)
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun triggerBlock(detail: String) {
        val now = System.currentTimeMillis()
        val key = detail.take(48)
        // анти-спам: не открывать заглушку каждые 100 мс
        if (key == lastKey && now - lastBlockAt < 3000) return
        lastKey = key
        lastBlockAt = now

        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(MainActivity.EXTRA_BLOCKED, true)
            putExtra(MainActivity.EXTRA_DETAIL, detail)
        }
        startActivity(i)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
    }
}
