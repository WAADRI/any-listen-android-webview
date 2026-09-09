package com.anylisten.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/** 播放页：WebView 加载 any-listen 网页版站点 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var baseHost: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        webView = findViewById(R.id.webview)

        val url = Prefs.getServerUrl(this)
        if (url == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        baseHost = Uri.parse(url).host

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        // 便于真机/模拟器远程调试（chrome://inspect）
        WebView.setWebContentsDebuggingEnabled(true)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val target = request?.url ?: return false
                val scheme = target.scheme
                if (scheme == "http" || scheme == "https") {
                    val base = baseHost
                    // 同站点（含子域）留在 WebView，外链交给系统浏览器
                    if (base == null || target.host == base || target.host?.endsWith(".$base") == true) {
                        return false
                    }
                    startActivity(Intent(Intent.ACTION_VIEW, target))
                    return true
                }
                return false
            }
        }
        webView.webChromeClient = WebChromeClient()

        webView.loadUrl(url)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
