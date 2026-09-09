package com.anylisten.mobile

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * addJavascriptInterface 注入对象：接收页面桥推送的播放状态。
 * 回调可能在非主线程触发，统一 post 到主线程。
 */
class JsBridge(private val onState: (MediaState) -> Unit) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onMediaState(json: String) {
        mainHandler.post {
            try {
                val o = JSONObject(json)
                val state = MediaState(
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    playing = o.optBoolean("playing", false),
                    positionMs = o.optLong("currentTime", 0L),
                    durationMs = o.optLong("duration", 0L)
                )
                onState(state)
            } catch (_: Exception) {
                // 忽略格式异常
            }
        }
    }
}
