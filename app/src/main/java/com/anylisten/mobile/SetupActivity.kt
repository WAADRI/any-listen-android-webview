package com.anylisten.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** 服务器地址设置页 */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val etUrl = findViewById<EditText>(R.id.etUrl)
        Prefs.getServerUrl(this)?.let { etUrl.setText(it) }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val normalized = normalizeUrl(etUrl.text.toString())
            if (normalized == null) {
                Toast.makeText(this, R.string.setup_error_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.setServerUrl(this, normalized)
            startActivity(Intent(this, PlayerActivity::class.java))
            finish()
        }
    }

    /** 规范化：无 scheme 时补 https://，去掉结尾斜杠 */
    private fun normalizeUrl(raw: String): String? {
        var u = raw.trim()
        if (u.isEmpty()) return null
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://$u"
        }
        while (u.endsWith("/")) u = u.dropLast(1)
        return if (u.length > "https://".length) u else null
    }
}
