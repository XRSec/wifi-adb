package com.xrsec.wifiadb

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.xrsec.wifiadb.core.AdbController
import com.xrsec.wifiadb.util.AppPreferences
import com.xrsec.wifiadb.util.LocaleHelper
import com.xrsec.wifiadb.util.PowerOptimizationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {

    // 顶部导航
    private lateinit var btnBack: ImageButton

    // 卡片一：语言偏好
    private lateinit var rgLanguage: RadioGroup
    private lateinit var rbLangSystem: RadioButton
    private lateinit var rbLangZh: RadioButton
    private lateinit var rbLangEn: RadioButton

    // 卡片三：自动化设置
    private lateinit var cbBootStart: CheckBox
    private lateinit var cbAutoConfirmAdb: CheckBox

    // 卡片四：系统保活与自启动
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnIgnoreBattery: Button
    private lateinit var btnOpenAutostart: Button

    // 卡片五：ADB 密钥管理
    private lateinit var tvAdbFingerprint: TextView
    private lateinit var etAdbPrivateKey: EditText
    private lateinit var etAdbPublicKey: EditText
    private lateinit var etAdbPassphrase: EditText
    private lateinit var btnSaveAdbKey: Button
    private lateinit var btnCopyPublicKey: Button
    private lateinit var btnRegenerateKey: Button

    // 底部保存
    private lateinit var btnSave: Button

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        loadPreferences()
        loadAdbKeyInfo()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryStatus()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btn_back_settings)

        // 卡片一
        rgLanguage = findViewById(R.id.rg_language)
        rbLangSystem = findViewById(R.id.rb_lang_system)
        rbLangZh = findViewById(R.id.rb_lang_zh)
        rbLangEn = findViewById(R.id.rb_lang_en)

        // 卡片三
        cbBootStart = findViewById(R.id.cb_boot_start)
        cbAutoConfirmAdb = findViewById(R.id.cb_auto_confirm_adb)

        // 卡片四
        tvBatteryStatus = findViewById(R.id.tv_battery_status)
        btnIgnoreBattery = findViewById(R.id.btn_ignore_battery)
        btnOpenAutostart = findViewById(R.id.btn_open_autostart)

        // 卡片五
        tvAdbFingerprint = findViewById(R.id.tv_adb_fingerprint)
        etAdbPrivateKey = findViewById(R.id.et_adb_private_key)
        etAdbPublicKey = findViewById(R.id.et_adb_public_key)
        etAdbPassphrase = findViewById(R.id.et_adb_passphrase)
        btnSaveAdbKey = findViewById(R.id.btn_save_adb_key)
        btnCopyPublicKey = findViewById(R.id.btn_copy_public_key)
        btnRegenerateKey = findViewById(R.id.btn_regenerate_adb_key)

        // 保存
        btnSave = findViewById(R.id.btn_save_settings)
    }

    private fun loadPreferences() {
        when (AppPreferences.getLanguage(this)) {
            AppPreferences.LANG_ZH -> rbLangZh.isChecked = true
            AppPreferences.LANG_EN -> rbLangEn.isChecked = true
            else -> rbLangSystem.isChecked = true
        }

        cbBootStart.isChecked = AppPreferences.isBootStartEnabled(this)
        cbAutoConfirmAdb.isChecked = AppPreferences.isAutoConfirmAdbEnabled(this)
        etAdbPassphrase.setText(AppPreferences.getAdbKeyPassphrase(this))
    }

    private fun loadAdbKeyInfo() {
        lifecycleScope.launch(Dispatchers.IO) {
            val fingerprint = AdbController.getAdbPublicKeyFingerprint(this@SettingsActivity)
            val privKey = AdbController.getAdbPrivateKeyString(this@SettingsActivity)
            val pubKey = AdbController.getAdbPublicKeyString(this@SettingsActivity)
            withContext(Dispatchers.Main) {
                tvAdbFingerprint.text = fingerprint
                etAdbPrivateKey.setText(privKey)
                etAdbPublicKey.setText(pubKey)
            }
        }
    }

    private fun refreshBatteryStatus() {
        val isIgnored = PowerOptimizationHelper.isIgnoringBatteryOptimizations(this)
        if (isIgnored) {
            tvBatteryStatus.text = getString(R.string.status_battery_ignored)
            tvBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            btnIgnoreBattery.isEnabled = false
        } else {
            tvBatteryStatus.text = getString(R.string.status_battery_optimizing)
            tvBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnIgnoreBattery.isEnabled = true
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
        }

        btnIgnoreBattery.setOnClickListener {
            val opened = PowerOptimizationHelper.requestIgnoreBatteryOptimizations(this)
            if (!opened) {
                Toast.makeText(this, getString(R.string.toast_battery_opt_not_found), Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenAutostart.setOnClickListener {
            val opened = PowerOptimizationHelper.openAutoStartManagement(this)
            if (!opened) {
                Toast.makeText(this, getString(R.string.toast_autostart_not_found), Toast.LENGTH_LONG).show()
            }
        }

        btnSaveAdbKey.setOnClickListener {
            val priv = etAdbPrivateKey.text.toString().trim()
            val pub = etAdbPublicKey.text.toString().trim()
            if (priv.isBlank() || pub.isBlank()) {
                Toast.makeText(this, getString(R.string.toast_key_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSaveAdbKey.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching {
                    AdbController.saveAdbKeyPairCustom(this@SettingsActivity, priv, pub)
                }
                withContext(Dispatchers.Main) {
                    btnSaveAdbKey.isEnabled = true
                    result.onSuccess { newFp ->
                        tvAdbFingerprint.text = newFp
                        Toast.makeText(this@SettingsActivity, getString(R.string.toast_key_saved), Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this@SettingsActivity, getString(R.string.toast_key_invalid), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnCopyPublicKey.setOnClickListener {
            val pub = etAdbPublicKey.text.toString().trim()
            if (pub.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("ADB Public Key", pub)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.toast_public_key_copied), Toast.LENGTH_SHORT).show()
            }
        }

        btnRegenerateKey.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_regenerate_key_title)
                .setMessage(R.string.dialog_regenerate_key_msg)
                .setPositiveButton(R.string.btn_confirm) { _, _ ->
                    btnRegenerateKey.isEnabled = false
                    lifecycleScope.launch(Dispatchers.IO) {
                        val (newPriv, newPub) = AdbController.generateNewAdbKeyPairStrings(this@SettingsActivity)
                        val newFingerprint = AdbController.getAdbPublicKeyFingerprint(this@SettingsActivity)
                        withContext(Dispatchers.Main) {
                            etAdbPrivateKey.setText(newPriv)
                            etAdbPublicKey.setText(newPub)
                            tvAdbFingerprint.text = newFingerprint
                            btnRegenerateKey.isEnabled = true
                            Toast.makeText(this@SettingsActivity, getString(R.string.toast_key_regenerated), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        btnSave.setOnClickListener {
            savePreferences()
        }
    }

    private fun savePreferences() {
        val previousLang = AppPreferences.getLanguage(this)
        val selectedLang = when (rgLanguage.checkedRadioButtonId) {
            R.id.rb_lang_zh -> AppPreferences.LANG_ZH
            R.id.rb_lang_en -> AppPreferences.LANG_EN
            else -> AppPreferences.LANG_SYSTEM
        }

        val newPassphrase = etAdbPassphrase.text.toString().trim()
        val currentPassphrase = AppPreferences.getAdbKeyPassphrase(this)

        AppPreferences.setBootStartEnabled(this, cbBootStart.isChecked)
        AppPreferences.setAutoConfirmAdbEnabled(this, cbAutoConfirmAdb.isChecked)
        AppPreferences.setLanguage(this, selectedLang)

        if (newPassphrase != currentPassphrase) {
            btnSave.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                AdbController.updateKeyPassphrase(this@SettingsActivity, newPassphrase)
                withContext(Dispatchers.Main) {
                    btnSave.isEnabled = true
                    finishSavePreferences(previousLang, selectedLang)
                }
            }
        } else {
            finishSavePreferences(previousLang, selectedLang)
        }
    }

    private fun finishSavePreferences(previousLang: String, selectedLang: String) {
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
