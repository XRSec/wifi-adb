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
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.xrsec.wifiadb.core.AdbController
import com.xrsec.wifiadb.core.StartupCoordinator
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
import com.xrsec.wifiadb.util.DeviceInfoHelper
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    // 顶部导航
    private lateinit var btnSettings: ImageButton
    private lateinit var btnAbout: ImageButton

    // 设备信息卡片
    private lateinit var tvDeviceModelSystem: TextView
    private lateinit var tvDeviceSerialAbi: TextView

    // 卡片一：系统权限
    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvPermissionDesc: TextView
    private lateinit var btnGrantPermission: Button

    // 卡片二：ADB 服务状态 (原无线调试)
    private lateinit var tvAdbStatus: TextView
    private lateinit var tvWifiName: TextView
    private lateinit var tvIpPort: TextView
    private lateinit var switchStartupWirelessDebug: Switch
    private lateinit var switchStartupRootWirelessDebug: Switch
    private lateinit var switchStartupDisableTimeout: Switch
    private lateinit var switchStartupRootDisableTimeout: Switch
    private lateinit var etFixedPort: EditText
    private lateinit var tvCurrentPort: TextView
    private lateinit var switchStartupRootFixPort: Switch
    private lateinit var switchStartupAdbFixPort: Switch
    private lateinit var switchAdb: Switch
    private lateinit var btnOpenWirelessSettings: Button

    // 卡片：无障碍权限
    private lateinit var tvA11yPermissionStatus: TextView
    private lateinit var tvA11yPermissionDesc: TextView
    private lateinit var btnGrantA11yPermission: Button

    // 卡片三：无障碍服务
    private lateinit var tvA11yStatus: TextView
    private lateinit var switchStartupAdbA11y: Switch
    private lateinit var switchStartupRootA11y: Switch
    private lateinit var btnA11yDadbGrant: Button
    private lateinit var btnOpenA11ySettings: Button
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
        refreshAccessibilityStatus()
        refreshPreferencesUi()

        // 启动时异步执行自动化决策链
        triggerStartupCoordinator()
    }

    override fun onResume() {
        super.onResume()
        // 仅注册统一内存回调，避免与系统广播接收器重复并发执行网络探测
        WifiStateReceiver.listener = { _, _, isConnected ->
            runOnUiThread {
                refreshNetworkAndAdbInfo()
                if (isConnected && NetworkUtils.isWifiConnected(this@MainActivity)) {
                    triggerStartupCoordinator()
                }
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

        // 设备信息
        tvDeviceModelSystem = findViewById(R.id.tv_device_model_system)
        tvDeviceSerialAbi = findViewById(R.id.tv_device_serial_abi)

        // 卡片一
        tvPermissionStatus = findViewById(R.id.tv_permission_status)
        tvPermissionDesc = findViewById(R.id.tv_permission_desc)
        btnGrantPermission = findViewById(R.id.btn_grant_permission)

        // 卡片二：ADB 服务状态
        tvAdbStatus = findViewById(R.id.tv_adb_status)
        tvWifiName = findViewById(R.id.tv_wifi_name)
        tvIpPort = findViewById(R.id.tv_ip_port)
        switchStartupWirelessDebug = findViewById(R.id.switch_startup_wireless_debug)
        switchStartupRootWirelessDebug = findViewById(R.id.switch_startup_root_wireless_debug)
        switchStartupDisableTimeout = findViewById(R.id.switch_startup_disable_timeout)
        switchStartupRootDisableTimeout = findViewById(R.id.switch_startup_root_disable_timeout)
        etFixedPort = findViewById(R.id.et_fixed_port)
        tvCurrentPort = findViewById(R.id.tv_current_port)
        switchStartupRootFixPort = findViewById(R.id.switch_startup_root_fix_port)
        switchStartupAdbFixPort = findViewById(R.id.switch_startup_adb_fix_port)
        switchAdb = findViewById(R.id.switch_adb)
        btnOpenWirelessSettings = findViewById(R.id.btn_open_wireless_settings)

        // 卡片：无障碍权限
        tvA11yPermissionStatus = findViewById(R.id.tv_a11y_permission_status)
        tvA11yPermissionDesc = findViewById(R.id.tv_a11y_permission_desc)
        btnGrantA11yPermission = findViewById(R.id.btn_grant_a11y_permission)

        // 卡片三：无障碍服务
        tvA11yStatus = findViewById(R.id.tv_a11y_status)
        switchStartupAdbA11y = findViewById(R.id.switch_startup_adb_a11y)
        switchStartupRootA11y = findViewById(R.id.switch_startup_root_a11y)
        btnA11yDadbGrant = findViewById(R.id.btn_a11y_dadb_grant)
        btnOpenA11ySettings = findViewById(R.id.btn_open_a11y_settings)
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

        // 卡片二：启动自启开关
        switchStartupWirelessDebug.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                AppPreferences.setStartupEnableWirelessDebug(this, isChecked)
            }
        }

        // 卡片二：启动 Root 自启开关
        switchStartupRootWirelessDebug.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                AppPreferences.setStartupRootEnableWirelessDebug(this, isChecked)
            }
        }

        // 卡片二：启动 禁用授权超时开关
        switchStartupDisableTimeout.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                AppPreferences.setStartupDisableAuthTimeout(this, isChecked)
            }
        }

        // 卡片二：启动 Root 禁用授权超时开关
        switchStartupRootDisableTimeout.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                AppPreferences.setStartupRootDisableAuthTimeout(this, isChecked)
            }
        }

        // 卡片二：固定端口输入与保存
        etFixedPort.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveFixedPortFromInput()
                etFixedPort.clearFocus()
                true
            } else {
                false
            }
        }
        etFixedPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveFixedPortFromInput()
            }
        }

        // 卡片二：启动 Root 固定端口开关
        switchStartupRootFixPort.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                AppPreferences.setStartupRootFixPort(this, isChecked)
            }
        }

        // 卡片二：启动 ADB 固定端口开关
        switchStartupAdbFixPort.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                AppPreferences.setStartupAdbFixPort(this, isChecked)
            }
        }

        // 卡片二：开发者设置无线调试开关
        switchAdb.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                toggleAdbWireless(isChecked)
            }
        }

        // 卡片二：跳转系统无线调试设置
        btnOpenWirelessSettings.setOnClickListener {
            val opened = AdbController.openWirelessDebuggingSettings(this)
            if (!opened) {
                Toast.makeText(this, getString(R.string.toast_cannot_open_settings), Toast.LENGTH_SHORT).show()
            }
        }

        // 卡片三：启动 ADB 激活无障碍开关
        switchStartupAdbA11y.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                AppPreferences.setStartupAdbActivateA11y(this, isChecked)
            }
        }

        // 卡片三：启动 Root 激活无障碍开关
        switchStartupRootA11y.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                AppPreferences.setStartupRootActivateA11y(this, isChecked)
            }
        }

        // 卡片三：授权权限按钮
        btnGrantA11yPermission.setOnClickListener {
            handleA11yPermissionGrant()
        }

        // 卡片三：dadb Shell 专属授权按钮
        btnA11yDadbGrant.setOnClickListener {
            handleA11yDadbGrant()
        }

        // 卡片三：跳转系统无障碍设置
        btnOpenA11ySettings.setOnClickListener {
            navigateToAccessibilitySettings()
        }

        // 卡片三：无障碍静默开关
        switchA11y.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                toggleAccessibilityService(isChecked)
            }
        }

        btnRefresh.setOnClickListener {
            refreshAll()
            triggerStartupCoordinator()
            Toast.makeText(this, getString(R.string.toast_status_refreshed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveFixedPortFromInput() {
        val text = etFixedPort.text.toString().trim()
        val port = text.toIntOrNull()
        if (port != null && port in 1024..65535) {
            AppPreferences.setFixedPort(this, port)
            refreshNetworkAndAdbInfo()
        } else {
            Toast.makeText(this, getString(R.string.toast_port_invalid), Toast.LENGTH_SHORT).show()
            etFixedPort.setText(AppPreferences.getFixedPort(this).toString())
        }
    }

    private fun refreshPreferencesUi() {
        isUpdatingUi = true
        switchStartupWirelessDebug.isChecked = AppPreferences.isStartupEnableWirelessDebug(this)
        switchStartupRootWirelessDebug.isChecked = AppPreferences.isStartupRootEnableWirelessDebug(this)
        switchStartupDisableTimeout.isChecked = AppPreferences.isStartupDisableAuthTimeout(this)
        switchStartupRootDisableTimeout.isChecked = AppPreferences.isStartupRootDisableAuthTimeout(this)
        if (!etFixedPort.hasFocus()) {
            etFixedPort.setText(AppPreferences.getFixedPort(this).toString())
        }
        switchStartupRootFixPort.isChecked = AppPreferences.isStartupRootFixPort(this)
        switchStartupAdbFixPort.isChecked = AppPreferences.isStartupAdbFixPort(this)
        switchStartupAdbA11y.isChecked = AppPreferences.isStartupAdbActivateA11y(this)
        switchStartupRootA11y.isChecked = AppPreferences.isStartupRootActivateA11y(this)
        isUpdatingUi = false
    }

    private fun triggerStartupCoordinator() {
        activityScope.launch {
            StartupCoordinator.executeStartupFlow(this@MainActivity, object : StartupCoordinator.Listener {
                override fun onStatusToast(resId: Int, vararg args: Any) {
                    runOnUiThread {
                        val msg = if (args.isEmpty()) getString(resId) else getString(resId, *args)
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onGuidePermissionNeeded() {
                    runOnUiThread {
                        if (isSafeForDialog()) {
                            showPermissionGuideDialog()
                        }
                    }
                }

                override fun onStateChanged() {
                    runOnUiThread {
                        refreshAll()
                    }
                }
            })
        }
    }

    private fun refreshAll() {
        refreshDeviceInfo()
        refreshPermissionStatus()
        refreshNetworkAndAdbInfo()
        refreshAccessibilityStatus()
        refreshPreferencesUi()
    }

    private fun refreshDeviceInfo() {
        val info = DeviceInfoHelper.getDeviceInfo(this)
        tvDeviceModelSystem.text = "${info.modelDisplayName}  |  ${info.systemVersionName}"
        tvDeviceSerialAbi.text = "Serial: ${info.serialNumber}  |  ABI: ${info.cpuAbi}"
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
                withContext(Dispatchers.IO) {
                    AdbController.disableAdbAuthTimeout(this@MainActivity)
                }
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
        val configuredPort = AppPreferences.getFixedPort(this)

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
        val configuredPort = AppPreferences.getFixedPort(this)

        activityScope.launch {
            val (ip, ssid) = withContext(Dispatchers.IO) {
                Pair(
                    NetworkUtils.getLocalIpAddress(this@MainActivity),
                    NetworkUtils.getWifiSsid(this@MainActivity)
                )
            }
            currentIp = ip
            val (isAdbActive, displayPort) = withContext(Dispatchers.IO) {
                val info = AdbController.getActivePortInfo(this@MainActivity, configuredPort)
                val portStr = if (info.isSystemWireless) {
                    if (info.port > 0) info.port.toString() else getString(R.string.port_system_dynamic)
                } else {
                    configuredPort.toString()
                }
                Pair(info.isActive, portStr)
            }

            tvWifiName.text = if (ssid.isNotBlank()) {
                getString(R.string.label_wifi_name, ssid)
            } else {
                getString(R.string.text_not_connected)
            }

            tvIpPort.text = getString(
                R.string.label_ip_port,
                ip ?: getString(R.string.text_unknown_ip),
                displayPort
            )

            tvCurrentPort.text = if (isAdbActive) displayPort else getString(R.string.status_adb_inactive)

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
        val configuredPort = AppPreferences.getFixedPort(this)

        activityScope.launch {
            // 异步操作期间临时禁用 Switch 控件，防止连续快速点击造成竞态
            switchAdb.isEnabled = false

            try {
                val success = withContext(Dispatchers.IO) {
                    AdbController.setWirelessDebuggingEnabled(this@MainActivity, enable, configuredPort)
                }
                if (success) {
                    val msg = if (enable) {
                        val isStaticListening = withContext(Dispatchers.IO) { AdbController.isPortListening(configuredPort) }
                        val isGlobalEnabled = withContext(Dispatchers.IO) { AdbController.isAdbWifiGlobalEnabled(this@MainActivity) }
                        val portStr = if (isGlobalEnabled && !isStaticListening) {
                            withContext(Dispatchers.IO) { AdbController.getDynamicTlsPort() }?.toString() ?: getString(R.string.port_system_dynamic)
                        } else {
                            configuredPort.toString()
                        }
                        getString(R.string.toast_adb_started, portStr)
                    } else {
                        getString(R.string.toast_adb_stopped)
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    AdbWirelessService.start(this@MainActivity, AdbWirelessService.ACTION_UPDATE_NOTIFICATION)
                } else {
                    if (enable) {
                        val hasPrivilege = withContext(Dispatchers.IO) {
                            AdbController.hasWriteSecureSettings(this@MainActivity) || AdbController.isRootAvailable()
                        }
                        if (!hasPrivilege) {
                            Toast.makeText(this@MainActivity, getString(R.string.toast_permission_required_for_wireless), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, getString(R.string.toast_adb_start_failed), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_adb_stop_failed), Toast.LENGTH_SHORT).show()
                    }
                    val currentActive = withContext(Dispatchers.IO) {
                        AdbController.isAdbWirelessActive(this@MainActivity, configuredPort)
                    }
                    isUpdatingUi = true
                    switchAdb.isChecked = currentActive
                    isUpdatingUi = false
                }
                refreshNetworkAndAdbInfo()
            } finally {
                switchAdb.isEnabled = true
            }
        }
    }

    // ==========================================
    // 卡片三：无障碍服务逻辑
    // ==========================================
    private fun refreshAccessibilityStatus() {
        val isA11yActive = WifiAdbAccessibilityService.isServiceEnabled(this)
        btnA11yDadbGrant.visibility = if (isA11yActive) View.GONE else View.VISIBLE
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

        val hasSecure = AdbController.hasWriteSecureSettings(this)
        btnGrantA11yPermission.visibility = View.VISIBLE
        if (hasSecure) {
            tvA11yPermissionStatus.text = getString(R.string.status_permission_granted)
            tvA11yPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            tvA11yPermissionDesc.text = getString(R.string.desc_a11y_permission_granted)
            btnGrantA11yPermission.text = getString(R.string.btn_permission_granted)
            btnGrantA11yPermission.isEnabled = false
        } else {
            tvA11yPermissionStatus.text = getString(R.string.status_permission_none)
            tvA11yPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            tvA11yPermissionDesc.text = getString(R.string.desc_a11y_permission_none)
            btnGrantA11yPermission.text = getString(R.string.btn_grant_a11y_permission)
            btnGrantA11yPermission.isEnabled = true
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
                        isUpdatingUi = true
                        switchA11y.isChecked = WifiAdbAccessibilityService.isServiceEnabled(this@MainActivity)
                        isUpdatingUi = false
                        navigateToAccessibilitySettings()
                    }
                } else {
                    isUpdatingUi = true
                    switchA11y.isChecked = WifiAdbAccessibilityService.isServiceEnabled(this@MainActivity)
                    isUpdatingUi = false
                    navigateToAccessibilitySettings()
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                isUpdatingUi = true
                switchA11y.isChecked = WifiAdbAccessibilityService.isServiceEnabled(this@MainActivity)
                isUpdatingUi = false
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

    private fun handleA11yDadbGrant() {
        btnA11yDadbGrant.isEnabled = false
        activityScope.launch {
            try {
                val isRoot = withContext(Dispatchers.IO) { AdbController.isRootAvailable() }
                val configuredPort = AppPreferences.getFixedPort(this@MainActivity)
                val activePort = withContext(Dispatchers.IO) {
                    val dynamic = AdbController.getDynamicTlsPort()
                    when {
                        dynamic != null && dynamic > 0 -> dynamic
                        AdbController.isPortListening(configuredPort) -> configuredPort
                        AdbController.isPortListening(5555) -> 5555
                        else -> null
                    }
                }

                if (!isRoot && activePort == null) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_a11y_dadb_grant_failed), Toast.LENGTH_LONG).show()
                    showDadbPortInputDialog(configuredPort)
                    return@launch
                }

                val targetPort = activePort ?: configuredPort
                val result = AdbController.grantAccessibilityViaDadbShell(this@MainActivity, targetPort)

                if (result.isSuccess || WifiAdbAccessibilityService.isServiceEnabled(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_a11y_dadb_grant_success), Toast.LENGTH_SHORT).show()
                    refreshAccessibilityStatus()
                    refreshPermissionStatus()
                } else {
                    val ex = result.exceptionOrNull()
                    val msg = ex?.message?.takeIf { it.isNotBlank() } ?: getString(R.string.toast_a11y_dadb_grant_failed)
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    refreshAccessibilityStatus()
                }
            } finally {
                btnA11yDadbGrant.isEnabled = true
            }
        }
    }

    private fun handleA11yPermissionGrant() {
        if (AdbController.hasWriteSecureSettings(this)) {
            Toast.makeText(this, getString(R.string.toast_permission_already_granted), Toast.LENGTH_SHORT).show()
            refreshAccessibilityStatus()
            refreshPermissionStatus()
            return
        }

        btnGrantA11yPermission.isEnabled = false
        activityScope.launch {
            try {
                // 1. 优先尝试 Root
                val rootAvailable = withContext(Dispatchers.IO) {
                    AdbController.isRootAvailable()
                }
                if (rootAvailable) {
                    val rootGranted = AdbController.grantWriteSecureSettingsWithRoot(this@MainActivity)
                    if (rootGranted) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_a11y_permission_grant_success), Toast.LENGTH_SHORT).show()
                        refreshAccessibilityStatus()
                        refreshPermissionStatus()
                        return@launch
                    }
                }

                // 2. 本地回环 Dadb 授权 (pm grant ... WRITE_SECURE_SETTINGS)
                val configuredPort = AppPreferences.getFixedPort(this@MainActivity)
                val activePort = withContext(Dispatchers.IO) {
                    val dynamic = AdbController.getDynamicTlsPort()
                    when {
                        dynamic != null && dynamic > 0 && AdbController.isPortListening(dynamic) -> dynamic
                        AdbController.isPortListening(configuredPort) -> configuredPort
                        AdbController.isPortListening(5555) -> 5555
                        else -> null
                    }
                }

                if (activePort != null) {
                    val result = AdbController.grantWriteSecureSettingsViaDadb(this@MainActivity, activePort)
                    if (result.isSuccess || AdbController.hasWriteSecureSettings(this@MainActivity)) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_a11y_permission_grant_success), Toast.LENGTH_SHORT).show()
                        refreshAccessibilityStatus()
                        refreshPermissionStatus()
                    } else {
                        val ex = result.exceptionOrNull()
                        if (isSafeForDialog()) {
                            showPermissionGuideDialog(errorMessage = ex?.message)
                        }
                    }
                } else {
                    showDadbPortInputDialog(configuredPort)
                }
            } finally {
                btnGrantA11yPermission.isEnabled = !AdbController.hasWriteSecureSettings(this@MainActivity)
            }
        }
    }
}
