package com.nodep.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Главный экран = WebView с UI из assets (как интерфейс расширения).
 *
 * JS-мост window.Nodep:
 *   Nodep.isAccessibilityEnabled()  → boolean
 *   Nodep.openAccessibilitySettings()
 *   Nodep.goHome()
 *   Nodep.getBlockDetail()          → string
 *
 * Если Intent с EXTRA_BLOCKED=true → грузим blocked.html
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var blockDetail: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Blocklist.load(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.allowFileAccess = true
            webViewClient = WebViewClient()
            // JS → Kotlin
            addJavascriptInterface(NodepJsBridge(), "Nodep")
        }
        setContentView(webView)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val blocked = intent?.getBooleanExtra(EXTRA_BLOCKED, false) == true
        blockDetail = intent?.getStringExtra(EXTRA_DETAIL) ?: ""
        val page = if (blocked) {
            "file:///android_asset/blocked.html"
        } else {
            "file:///android_asset/index.html"
        }
        webView.loadUrl(page)
    }

    /** Мост для JavaScript из HTML */
    inner class NodepJsBridge {
        @JavascriptInterface
        fun isAccessibilityEnabled(): Boolean {
            return isA11yEnabled(this@MainActivity)
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        @JavascriptInterface
        fun goHome() {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(home)
        }

        @JavascriptInterface
        fun getBlockDetail(): String = blockDetail
    }

    companion object {
        const val EXTRA_BLOCKED = "blocked"
        const val EXTRA_DETAIL = "detail"

        fun isA11yEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains(ctx.packageName)
        }
    }
}
