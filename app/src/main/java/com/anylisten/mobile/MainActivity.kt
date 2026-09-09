package com.anylisten.mobile

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** 启动分发：未配置服务器地址 -> 设置页；已配置 -> 播放页 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (Prefs.getServerUrl(this) == null) {
            Intent(this, SetupActivity::class.java)
        } else {
            Intent(this, PlayerActivity::class.java)
        }
        startActivity(target)
        finish()
    }
}
