package com.anylisten.mobile

import android.content.Context

/** 简单配置存储：服务器地址等 */
object Prefs {
    private const val NAME = "config"
    private const val KEY_SERVER_URL = "server_url"

    fun getServerUrl(context: Context): String? =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, null)
            ?.takeIf { it.isNotBlank() }

    fun setServerUrl(context: Context, url: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, url.trim())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SERVER_URL)
            .apply()
    }
}
