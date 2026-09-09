package com.anylisten.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.content.pm.PackageManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 播放页：WebView 加载 any-listen 网页版站点，并注入 JS 桥
 * 把页面播放状态同步到原生 MediaService（通知栏/锁屏），
 * 以及把通知栏/锁屏控制指令转发回页面（走页面自身的 mediaSession 处理函数）。
 */
class PlayerActivity : AppCompatActivity(), MediaCommandSink {

    private companion object {
        private const val REQ_NOTIFICATION = 100
    }

    private lateinit var webView: WebView
    private var baseHost: String? = null
    private var bridgeJs: String? = null
    private var pendingState: MediaState? = null
    private var serviceBound = false
    private var serviceRequested = false
    private var mediaService: MediaService? = null

    private val jsBridge = JsBridge { state -> onPageState(state) }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val s = (service as MediaService.LocalBinder).getService()
            mediaService = s
            s.attachSink(this@PlayerActivity)
            pendingState?.let { s.publish(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaService = null
        }
    }

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

        // 页面 -> 原生 状态通道
        webView.addJavascriptInterface(jsBridge, "anyListenNative")

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

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectBridge()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 兜底重注（脚本幂等）
                injectBridge()
            }
        }
        webView.webChromeClient = WebChromeClient()

        webView.loadUrl(url)
    }

    override fun onStart() {
        super.onStart()
        if (!serviceBound) {
            bindService(Intent(this, MediaService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
            serviceBound = true
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        // 播放中退后台：不暂停 WebView，保证音频/事件持续；仅在未播放时省电
        if (pendingState?.playing != true) {
            webView.onPause()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else if (pendingState?.playing == true) {
            // 播放中按返回：退到后台继续播，避免误关
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (serviceBound) {
            serviceBound = false
            mediaService?.attachSink(null)
            mediaService = null
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {
            }
        }
        MediaService.stop(this)
        try {
            webView.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ---------- 页面状态上行 ----------

    private fun onPageState(state: MediaState) {
        pendingState = state
        if (state.playing) {
            ensureMediaService()
        }
        mediaService?.publish(state)
    }

    private fun ensureMediaService() {
        requestNotificationPermissionIfNeeded()
        if (!serviceBound || serviceRequested) return
        serviceRequested = true
        // startForegroundService + publish(playing) 会把服务提升为前台媒体服务
        ContextCompat.startForegroundService(this, Intent(this, MediaService::class.java))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATION
            )
        }
    }

    // ---------- 原生指令下行（MediaCommandSink） ----------

    override fun onPlayCommand() = runBridge("play")

    override fun onPauseCommand() = runBridge("pause")

    override fun onNextCommand() = runBridge("next")

    override fun onPrevCommand() = runBridge("prev")

    override fun onSeekCommand(positionMs: Long) {
        runBridge("seek", (positionMs / 1000.0).toString())
    }

    private fun runBridge(method: String, argText: String? = null) {
        if (!::webView.isInitialized || isDestroyed || isFinishing) return
        webView.post {
            try {
                val expr = buildString {
                    append("window.__anylistenBridge && window.__anylistenBridge.")
                    append(method)
                    append(" && window.__anylistenBridge.")
                    append(method)
                    if (argText != null) append("(").append(argText).append(")") else append("()")
                }
                webView.evaluateJavascript(expr, null)
            } catch (_: Exception) {
            }
        }
    }

    // ---------- 注入 ----------

    private fun injectBridge() {
        if (!::webView.isInitialized) return
        val js = bridgeJs ?: run {
            val text = assets.open("bridge.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
            bridgeJs = text
            text
        }
        try {
            webView.evaluateJavascript(js, null)
        } catch (_: Exception) {
        }
    }
}
