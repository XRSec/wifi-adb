package com.xrsec.wifiadb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.xrsec.wifiadb.util.LocaleHelper

class AboutActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val btnBack = findViewById<ImageButton>(R.id.btn_back_about)
        btnBack.setOnClickListener {
            finish()
        }

        val tvVersion = findViewById<TextView>(R.id.tv_app_version)
        tvVersion.text = getString(R.string.app_version_format, BuildConfig.VERSION_NAME)

        val btnOpenGithub = findViewById<Button>(R.id.btn_open_github)
        btnOpenGithub.setOnClickListener {
            try {
                val url = getString(R.string.github_url)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (_: Throwable) {
                Toast.makeText(this, "无法打开系统浏览器", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
