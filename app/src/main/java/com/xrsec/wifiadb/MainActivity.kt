package com.xrsec.wifiadb

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.xrsec.wifiadb.core.AdbController
import com.xrsec.wifiadb.service.AdbWirelessService
import com.xrsec.wifiadb.service.WifiAdbAccessibilityService
import com.xrsec.wifiadb.service.WifiStateReceiver
import com.xrsec.wifiadb.util.AppPreferences
import com.xrsec.wifiadb.util.LocaleHelper
import com.xrsec.wifiadb.util.NetworkUtils
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    // 顶部导航
    private lateinit var btnSettings: ImageButton
    private lateinit var btnAbout: ImageButton

    // 卡片一：系统权限
    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvPermissionDesc: TextView
    private lateinit var btnGrantPermission: Button

    // 卡片二：无线调试
    private lateinit var tvAdbStatus: TextView
    private lateinit var tvWifiName: TextView
    private lateinit var tvIpPort: TextView
    private lateinit var layoutCommandBox: View
    private lateinit var tvCommand: TextView
    private lateinit var btnConfigAdb: Button
    private lateinit var switchAdb: Switch

    // 卡片三：无障碍服务
    private lateinit var tvA11yStatus: TextView
    private lateinit var btnConfigA11y: Button
    private lateinit var switchA11y: Switch

    // 底部刷新
    private lateinit var btnRefresh: Button

    // 采用 SupervisorJob 防止子协程异常扩散导致生命周期协程作用域失效
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentIp: String? = null
    private var isUpdatingUi = false

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
        // 仅注册统一内存回调，避免与系统广播接收器重复并发执行网络探测
        WifiStateReceiver.listener = { _, _, _ ->
            runOnUiThread {
                refreshNetworkAndAdbInfo()
            }
        }
        refreshAll()
    }

    override fun onPause() {
        super.onPause()
        WifiStateReceiver.listener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun initViews() {
        btnSettings = findViewById(R.id.btn_settings)
        btnAbout = findViewById(R.id.btn_about)

        // 卡片一
        tvPermissionStatus = findViewById(R.id.tv_permission_status)
        tvPermissionDesc = findViewById(R.id.tv_permission_desc)
        btnGrantPermission = findViewById(R.id.btn_grant_permission)

        // 卡片二
        tvAdbStatus = findViewById(R.id.tv_adb_status)
        tvWifiName = findViewById(R.id.tv_wifi_name)
        tvIpPort = findViewById(R.id.tv_ip_port)
        layoutCommandBox = findViewById(R.id.layout_command_box)
        tvCommand = findViewById(R.id.tv_command)
        btnConfigAdb = findViewById(R.id.btn_config_adb)
        switchAdb = findViewById(R.id.switch_adb)

        // 卡片三
        tvA11yStatus = findViewById(R.id.tv_a11y_status)
        btnConfigA11y = findViewById(R.id.btn_config_a11y)
        switchA11y = findViewById(R.id.switch_a11y)

        btnRefresh = findViewById(R.id.btn_refresh)
    }

    private fun setupListeners() {
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // 卡片一：点击授权
        btnGrantPermission.setOnClickListener {
            handleGrantPermission()
        }

        // 卡片二：配置（直达系统无线调试设置）与开关
        btnConfigAdb.setOnClickListener {
            val opened = AdbController.openWirelessDebuggingSettings(this)
            if (!opened) {
                Toast.makeText(this, getString(R.string.toast_cannot_open_settings), Toast.LENGTH_SHORT).show()
            }
        }

        layoutCommandBox.setOnClickListener {
            copyAdbCommand()
        }

        switchAdb.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                toggleAdbWireless(isChecked)
            }
        }

        // 卡片三：配置（直达系统无障碍设置）与无障碍开关
        btnConfigA11y.setOnClickListener {
            navigateToAccessibilitySettings()
        }

        switchA11y.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                toggleAccessibilityService(isChecked)
            }
        }

        btnRefresh.setOnClickListener {
            refreshAll()
            Toast.makeText(this, getString(R.string.toast_status_refreshed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshAll() {
        refreshPermissionStatus()
        refreshNetworkAndAdbInfo()
        refreshAccessibilityStatus()
    }

    // ==========================================
    // 卡片一：系统权限逻辑 (WRITE_SECURE_SETTINGS)
    // ==========================================
    private fun refreshPermissionStatus() {
        activityScope.launch {
            val hasSecure = withContext(Dispatchers.IO) {
                AdbController.hasWriteSecureSettings(this@MainActivity)
            }
            val hasRoot = withContext(Dispatchers.IO) {
                AdbController.isRootAvailable()
            }

            if (hasSecure || hasRoot) {
                val statusText = if (hasRoot) {
                    getString(R.string.status_permission_root)
                } else {
                    getString(R.string.status_permission_granted)
                }
                tvPermissionStatus.text = statusText
                tvPermissionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                tvPermissionDesc.text = getString(R.string.desc_permission_granted)
                btnGrantPermission.text = getString(R.string.btn_permission_granted)
                btnGrantPermission.isEnabled = false
            } else {
                tvPermissionStatus.text = getString(R.string.status_permission_none)
                tvPermissionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
                tvPermissionDesc.text = getString(R.string.desc_permission_none)
                btnGrantPermission.text = getString(R.string.btn_grant_permission)
                btnGrantPermission.isEnabled = true
            }
        }
    }

    private fun handleGrantPermission() {
        if (AdbController.hasWriteSecureSettings(this)) {
            Toast.makeText(this, getString(R.string.toast_permission_already_granted), Toast.LENGTH_SHORT).show()
            refreshAll()
            return
        }

        btnGrantPermission.isEnabled = false
        btnGrantPermission.text = getString(R.string.btn_checking_permission)

        activityScope.launch {
            // 快速路径：若设备已具备 Root 权限，直接通过 Root 授权
            val rootAvailable = withContext(Dispatchers.IO) {
                AdbController.isRootAvailable()
            }

            if (rootAvailable) {
                val rootGranted = AdbController.grantWriteSecureSettingsWithRoot(this@MainActivity)
                if (rootGranted) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_root_grant_success), Toast.LENGTH_SHORT).show()
                    refreshAll()
                    return@launch
                }
            }

            // 非 Root 路径：利用 dadb 本地握手闭环授权
            btnGrantPermission.isEnabled = true
            btnGrantPermission.text = getString(R.string.btn_grant_permission)

            // 检查无障碍服务状态：若未开启，友好提示其先开启无障碍以享受全自动确认
            val isA11yActive = WifiAdbAccessibilityService.isServiceEnabled(this@MainActivity)
            if (!isA11yActive) {
                showA11yAutoConfirmPrompt()
            } else {
                startDadbGrantFlow()
            }
        }
    }

    private fun isSafeForDialog(): Boolean {
        return !isFinishing && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !isDestroyed)
    }

    private fun dismissDialogSafely(dialog: AlertDialog?) {
        if (dialog != null && isSafeForDialog() && dialog.isShowing) {
            try {
                dialog.dismiss()
            } catch (_: Throwable) {}
        }
    }

    private fun showA11yAutoConfirmPrompt() {
        if (!isSafeForDialog()) return

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_a11y_prompt_title)
            .setMessage(R.string.dialog_a11y_prompt_msg)
            .setPositiveButton(R.string.btn_go_to_a11y) { _, _ ->
                navigateToAccessibilitySettings()
            }
            .setNeutralButton(R.string.btn_continue_manual) { _, _ ->
                startDadbGrantFlow()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun startDadbGrantFlow() {
        val configuredPort = AppPreferences.getPort(this)

        activityScope.launch {
            val isConfiguredPortListening = withContext(Dispatchers.IO) {
                AdbController.isPortListening(configuredPort)
            }
            val is5555Listening = if (configuredPort != 5555) {
                withContext(Dispatchers.IO) { AdbController.isPortListening(5555) }
            } else isConfiguredPortListening

            val activePort = when {
                isConfiguredPortListening -> configuredPort
                is5555Listening -> 5555
                else -> null
            }

            if (activePort != null) {
                // 已检测到活跃调试端口，直接发起 dadb 握手授权
                executeDadbAuthorization(activePort)
            } else {
                // 尚未检测到标准端口监听，弹出无线调试端口输入与跳转引导对话框
                showDadbPortInputDialog(configuredPort)
            }
        }
    }

    private fun showDadbPortInputDialog(defaultPort: Int) {
        if (!isSafeForDialog()) return

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(defaultPort.toString())
            setSelection(text.length)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_dadb_port_title)
            .setMessage(R.string.dialog_dadb_port_msg)
            .setView(input)
            .setNeutralButton(R.string.btn_open_wireless_settings, null)
            .setPositiveButton(R.string.btn_start_auth, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.show()

        // 重写 Neutral 按钮点击事件，使其仅启动跳转系统无线调试界面而不自动 dismiss() 对话框
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
            val opened = AdbController.openWirelessDebuggingSettings(this)
            if (!opened) {
                Toast.makeText(this, getString(R.string.toast_cannot_open_settings), Toast.LENGTH_SHORT).show()
            }
        }

        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            val port = input.text.toString().trim().toIntOrNull()
            if (port == null || port < 1024 || port > 65535) {
                Toast.makeText(this, getString(R.string.toast_port_invalid), Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                executeDadbAuthorization(port)
            }
        }
    }

    private fun executeDadbAuthorization(port: Int) {
        if (!isSafeForDialog()) return

        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.btn_checking_permission)
            .setMessage(getString(R.string.progress_dadb_auth, port))
            .setCancelable(false)
            .show()

        activityScope.launch {
            try {
                val isListening = withContext(Dispatchers.IO) {
                    AdbController.isPortListening(port, timeoutMs = 1500)
                }
                if (!isListening) {
                    dismissDialogSafely(progressDialog)
                    if (isSafeForDialog()) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.toast_port_not_listening, port),
                            Toast.LENGTH_LONG
                        ).show()
                        showDadbPortInputDialog(port)
                    }
                    return@launch
                }

                val result = AdbController.grantWriteSecureSettingsViaDadb(this@MainActivity, port)
                dismissDialogSafely(progressDialog)

                if (result.isSuccess || AdbController.hasWriteSecureSettings(this@MainActivity)) {
                    if (isSafeForDialog()) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.toast_dadb_grant_success),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    refreshAll()
                } else {
                    val ex = result.exceptionOrNull()
                    if (isSafeForDialog()) {
                        showPermissionGuideDialog(errorMessage = ex?.message)
                    }
                    refreshAll()
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                dismissDialogSafely(progressDialog)
                if (isSafeForDialog()) {
                    showPermissionGuideDialog(errorMessage = e.message)
                }
                refreshAll()
            }
        }
    }

    private fun showPermissionGuideDialog(errorMessage: String? = null) {
        if (!isSafeForDialog()) return

        val message = if (!errorMessage.isNullOrBlank()) {
            "${getString(R.string.dialog_permission_guide_msg)}\n\n(错误详情: $errorMessage)"
        } else {
            getString(R.string.dialog_permission_guide_msg)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_permission_guide_title)
            .setMessage(message)
            .setPositiveButton(R.string.btn_copy_grant_cmd) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("ADB Grant Command", getString(R.string.grant_cmd_text))
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.toast_grant_cmd_copied), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ==========================================
    // 卡片二：无线调试逻辑
    // ==========================================
    private fun refreshNetworkAndAdbInfo() {
        val configuredPort = AppPreferences.getPort(this)

        activityScope.launch {
            val (ip, ssid) = withContext(Dispatchers.IO) {
                Pair(
                    NetworkUtils.getLocalIpAddress(this@MainActivity),
                    NetworkUtils.getWifiSsid(this@MainActivity)
                )
            }
            currentIp = ip
            val isAdbActive = withContext(Dispatchers.IO) {
                AdbController.isAdbWirelessActive(configuredPort)
            }

            tvWifiName.text = if (ssid.isNotBlank()) {
                getString(R.string.label_wifi_name, ssid)
            } else {
                getString(R.string.text_not_connected)
            }

            tvIpPort.text = getString(
                R.string.label_ip_port,
                ip ?: getString(R.string.text_unknown_ip),
                configuredPort
            )

            val commandText = if (!ip.isNullOrBlank()) {
                NetworkUtils.formatAdbConnectCommand(ip, configuredPort)
            } else {
                "adb connect 192.168.x.x:$configuredPort"
            }
            tvCommand.text = commandText

            isUpdatingUi = true
            if (isAdbActive) {
                tvAdbStatus.text = getString(R.string.status_adb_active)
                tvAdbStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                switchAdb.isChecked = true
            } else {
                tvAdbStatus.text = getString(R.string.status_adb_inactive)
                tvAdbStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_gray))
                switchAdb.isChecked = false
            }
            isUpdatingUi = false
        }
    }

    private fun toggleAdbWireless(enable: Boolean) {
        val configuredPort = AppPreferences.getPort(this)

        activityScope.launch {
            val hasRoot = withContext(Dispatchers.IO) { AdbController.isRootAvailable() }
            if (!hasRoot && enable) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_root_required), Toast.LENGTH_LONG).show()
            }

            // 异步操作期间临时禁用 Switch 控件，防止连续快速点击造成多 Root Shell 竞态
            switchAdb.isEnabled = false

            try {
                if (enable) {
                    val success = AdbController.startWirelessAdb(configuredPort)
                    if (success) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_adb_started, configuredPort), Toast.LENGTH_SHORT).show()
                        AdbWirelessService.start(this@MainActivity, AdbWirelessService.ACTION_UPDATE_NOTIFICATION)
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_adb_start_failed), Toast.LENGTH_LONG).show()
                    }
                } else {
                    val success = AdbController.stopWirelessAdb(configuredPort)
                    if (success) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_adb_stopped), Toast.LENGTH_SHORT).show()
                        AdbWirelessService.start(this@MainActivity, AdbWirelessService.ACTION_UPDATE_NOTIFICATION)
                    }
                }
                refreshNetworkAndAdbInfo()
            } finally {
                switchAdb.isEnabled = true
            }
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

    // ==========================================
    // 卡片三：无障碍服务逻辑
    // ==========================================
    private fun refreshAccessibilityStatus() {
        val isA11yActive = WifiAdbAccessibilityService.isServiceEnabled(this)
        isUpdatingUi = true
        if (isA11yActive) {
            tvA11yStatus.text = getString(R.string.status_a11y_active)
            tvA11yStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            switchA11y.isChecked = true
        } else {
            tvA11yStatus.text = getString(R.string.status_a11y_inactive)
            tvA11yStatus.setTextColor(ContextCompat.getColor(this, R.color.status_gray))
            switchA11y.isChecked = false
        }
        isUpdatingUi = false
    }

    private fun toggleAccessibilityService(enable: Boolean) {
        // 异步切换期间临时禁用 Switch 控件，防止连续点击竞态
        switchA11y.isEnabled = false

        activityScope.launch {
            try {
                val hasSecure = withContext(Dispatchers.IO) {
                    AdbController.hasWriteSecureSettings(this@MainActivity)
                }
                val hasRoot = withContext(Dispatchers.IO) {
                    AdbController.isRootAvailable()
                }

                if (hasSecure || hasRoot) {
                    val success = AdbController.setAccessibilityServiceSilent(this@MainActivity, enable)
                    if (success) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_a11y_silent_success), Toast.LENGTH_SHORT).show()
                        refreshAccessibilityStatus()
                    } else {
                        navigateToAccessibilitySettings()
                    }
                } else {
                    navigateToAccessibilitySettings()
                }
            } finally {
                switchA11y.isEnabled = true
            }
        }
    }

    private fun navigateToAccessibilitySettings() {
        try {
            Toast.makeText(this, getString(R.string.toast_a11y_jump_settings), Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, getString(R.string.toast_cannot_open_accessibility), Toast.LENGTH_SHORT).show()
        }
    }
}
