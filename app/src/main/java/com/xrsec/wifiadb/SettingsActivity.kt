package com.xrsec.wifiadb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import com.xrsec.wifiadb.util.AppPreferences
import com.xrsec.wifiadb.util.LocaleHelper

class SettingsActivity : Activity() {

    private lateinit var btnBack: ImageButton
    private lateinit var rgLanguage: RadioGroup
    private lateinit var rbLangSystem: RadioButton
    private lateinit var rbLangZh: RadioButton
    private lateinit var rbLangEn: RadioButton
    private lateinit var etPort: EditText
    private lateinit var cbBootStart: CheckBox
    private lateinit var cbAutoConfirmAdb: CheckBox
    private lateinit var btnSave: Button

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        loadPreferences()
        setupListeners()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btn_back_settings)
        rgLanguage = findViewById(R.id.rg_language)
        rbLangSystem = findViewById(R.id.rb_lang_system)
        rbLangZh = findViewById(R.id.rb_lang_zh)
        rbLangEn = findViewById(R.id.rb_lang_en)
        etPort = findViewById(R.id.et_port)
        cbBootStart = findViewById(R.id.cb_boot_start)
        cbAutoConfirmAdb = findViewById(R.id.cb_auto_confirm_adb)
        btnSave = findViewById(R.id.btn_save_settings)
    }

    private fun loadPreferences() {
        when (AppPreferences.getLanguage(this)) {
            AppPreferences.LANG_ZH -> rbLangZh.isChecked = true
            AppPreferences.LANG_EN -> rbLangEn.isChecked = true
            else -> rbLangSystem.isChecked = true
        }

        etPort.setText(AppPreferences.getPort(this).toString())
        cbBootStart.isChecked = AppPreferences.isBootStartEnabled(this)
        cbAutoConfirmAdb.isChecked = AppPreferences.isAutoConfirmAdbEnabled(this)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
        }

        btnSave.setOnClickListener {
            savePreferences()
        }
    }

    private fun savePreferences() {
        val portStr = etPort.text.toString().trim()
        val port = portStr.toIntOrNull()
        if (port == null || port < 1024 || port > 65535) {
            Toast.makeText(this, getString(R.string.toast_port_invalid), Toast.LENGTH_SHORT).show()
            return
        }

        val previousLang = AppPreferences.getLanguage(this)
        val selectedLang = when (rgLanguage.checkedRadioButtonId) {
            R.id.rb_lang_zh -> AppPreferences.LANG_ZH
            R.id.rb_lang_en -> AppPreferences.LANG_EN
            else -> AppPreferences.LANG_SYSTEM
        }

        AppPreferences.setPort(this, port)
        AppPreferences.setBootStartEnabled(this, cbBootStart.isChecked)
        AppPreferences.setAutoConfirmAdbEnabled(this, cbAutoConfirmAdb.isChecked)
        AppPreferences.setLanguage(this, selectedLang)

        Toast.makeText(this, getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()

        if (previousLang != selectedLang) {
            // 语言发生变化，通知整应用刷新
            LocaleHelper.applyLocale(this, selectedLang)
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        } else {
            finish()
        }
    }
}
