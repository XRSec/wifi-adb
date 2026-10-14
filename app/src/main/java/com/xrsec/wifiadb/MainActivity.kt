package com.xrsec.wifiadb

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.xrsec.wifiadb.core.AdbController
import com.xrsec.wifiadb.core.ShellUtils
import com.xrsec.wifiadb.service.AdbWirelessService
import com.xrsec.wifiadb.service.WifiAdbAccessibilityService
import com.xrsec.wifiadb.service.WifiStateReceiver
import com.xrsec.wifiadb.util.AppPreferences
import com.xrsec.wifiadb.util.LocaleHelper
import com.xrsec.wifiadb.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private lateinit var btnSettings: ImageButton
    private lateinit var btnAbout: ImageButton
    private lateinit var tvWifiSsid: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var tvSectionAdbTitle: TextView
    private lateinit var tvAdbStatus: TextView
    private lateinit var tvRootStatus: TextView
    private lateinit var btnToggleAdb: Button
    private lateinit var tvCommand: TextView
    private lateinit var btnCopyCommand: Button
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnOpenAccessibility: Button
    private lateinit var btnRefresh: Button

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private var isAdbActive: Boolean = false
    private var isRootGranted: Boolean = false
    private var currentIp: String? = null

    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshNetworkStatus()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        WifiStateReceiver.listener = { _, _, _ ->
            runOnUiThread {
                refreshNetworkStatus()
            }
        }
        val filter = IntentFilter(WifiStateReceiver.ACTION_NETWORK_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(networkReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(networkReceiver, filter)
        }
        refreshAll()
    }

    override fun onPause() {
        super.onPause()
        WifiStateReceiver.listener = null
        try {
            unregisterReceiver(networkReceiver)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun initViews() {
        btnSettings = findViewById(R.id.btn_settings)
        btnAbout = findViewById(R.id.btn_about)
        tvWifiSsid = findViewById(R.id.tv_wifi_ssid)
        tvIpAddress = findViewById(R.id.tv_ip_address)
        tvSectionAdbTitle = findViewById(R.id.tv_section_adb_title)
        tvAdbStatus = findViewById(R.id.tv_adb_status)
        tvRootStatus = findViewById(R.id.tv_root_status)
        btnToggleAdb = findViewById(R.id.btn_toggle_adb)
        tvCommand = findViewById(R.id.tv_command)
        btnCopyCommand = findViewById(R.id.btn_copy_command)
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        btnOpenAccessibility = findViewById(R.id.btn_open_accessibility)
        btnRefresh = findViewById(R.id.btn_refresh)
    }

    private fun setupListeners() {
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        btnToggleAdb.setOnClickListener {
            toggleAdbWireless()
        }

        btnCopyCommand.setOnClickListener {
            copyAdbCommand()
        }

        btnOpenAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        btnRefresh.setOnClickListener {
            refreshAll()
            Toast.makeText(this, getString(R.string.toast_status_refreshed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshAll() {
        val configuredPort = AppPreferences.getPort(this)
        tvSectionAdbTitle.text = getString(R.string.section_adb_title, configuredPort)

        refreshNetworkStatus()
        refreshAccessibilityStatus()
        refreshRootAndAdbStatus(configuredPort)
    }

    private fun refreshNetworkStatus() {
        val configuredPort = AppPreferences.getPort(this)
        activityScope.launch {
            val (ip, ssid) = withContext(Dispatchers.IO) {
                Pair(
                    NetworkUtils.getLocalIpAddress(this@MainActivity),
                    NetworkUtils.getWifiSsid(this@MainActivity)
                )
            }
            currentIp = ip
            tvWifiSsid.text = if (ssid.isNotBlank()) ssid else getString(R.string.text_not_connected)
            tvIpAddress.text = ip ?: getString(R.string.text_unknown_ip)

            val commandText = if (!ip.isNullOrBlank()) {
                NetworkUtils.formatAdbConnectCommand(ip, configuredPort)
            } else {
                "adb connect 192.168.x.x:$configuredPort"
            }
            tvCommand.text = commandText
        }
    }

    private fun refreshAccessibilityStatus() {
        val isA11yActive = WifiAdbAccessibilityService.isServiceEnabled(this)
        if (isA11yActive) {
            tvAccessibilityStatus.text = getString(R.string.status_accessibility_enabled)
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            tvAccessibilityStatus.text = getString(R.string.status_accessibility_disabled)
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_gray))
        }
    }

    private fun refreshRootAndAdbStatus(port: Int) {
        activityScope.launch {
            val rootAvailable = withContext(Dispatchers.IO) {
                ShellUtils.isRootAvailable()
            }
            val adbActive = withContext(Dispatchers.IO) {
                AdbController.isAdbWirelessActive(port)
            }

            isRootGranted = rootAvailable
            isAdbActive = adbActive

            // 更新 Root 显示
            if (rootAvailable) {
                tvRootStatus.text = getString(R.string.status_root_granted)
                tvRootStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
            } else {
                tvRootStatus.text = getString(R.string.status_root_denied)
                tvRootStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
            }

            // 更新 ADB 状态与切换按钮
            if (adbActive) {
                tvAdbStatus.text = getString(R.string.status_adb_running, port)
                tvAdbStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                btnToggleAdb.text = getString(R.string.btn_stop_adb)
                btnToggleAdb.setBackgroundResource(R.drawable.bg_button_danger)
            } else {
                tvAdbStatus.text = getString(R.string.status_adb_stopped)
                tvAdbStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_gray))
                btnToggleAdb.text = getString(R.string.btn_start_adb)
                btnToggleAdb.setBackgroundResource(R.drawable.bg_button_primary)
            }
        }
    }

    private fun toggleAdbWireless() {
        val configuredPort = AppPreferences.getPort(this)

        if (!isRootGranted && !isAdbActive) {
            Toast.makeText(this, getString(R.string.toast_root_required), Toast.LENGTH_LONG).show()
        }

        btnToggleAdb.isEnabled = false
        btnToggleAdb.text = getString(R.string.btn_operating)

        activityScope.launch {
            if (isAdbActive) {
                val success = AdbController.stopWirelessAdb()
                if (success) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_adb_stopped), Toast.LENGTH_SHORT).show()
                    AdbWirelessService.start(this@MainActivity, AdbWirelessService.ACTION_UPDATE_NOTIFICATION)
                }
            } else {
                val success = AdbController.startWirelessAdb(configuredPort)
                if (success) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_adb_started, configuredPort), Toast.LENGTH_SHORT).show()
                    AdbWirelessService.start(this@MainActivity, AdbWirelessService.ACTION_UPDATE_NOTIFICATION)
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_adb_start_failed), Toast.LENGTH_LONG).show()
                }
            }

            btnToggleAdb.isEnabled = true
            refreshAll()
        }
    }

    private fun copyAdbCommand() {
        val ip = currentIp
        val configuredPort = AppPreferences.getPort(this)
        if (ip.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.toast_no_ip), Toast.LENGTH_SHORT).show()
            return
        }

        val command = NetworkUtils.formatAdbConnectCommand(ip, configuredPort)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            val clip = ClipData.newPlainText("ADB Command", command)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, getString(R.string.toast_cannot_open_accessibility), Toast.LENGTH_SHORT).show()
        }
    }
}
