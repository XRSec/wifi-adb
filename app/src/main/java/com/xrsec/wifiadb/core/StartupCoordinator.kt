package com.xrsec.wifiadb.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.xrsec.wifiadb.R
import com.xrsec.wifiadb.service.WifiAdbAccessibilityService
import com.xrsec.wifiadb.util.AppPreferences
import com.xrsec.wifiadb.util.NetworkUtils
import dadb.Dadb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * App 启动自动化决策链与状态协调器 (Startup Automation State Machine)
 * 严格按照架构方案实现两分支判定树：
 * 分支一 (adbd 已启动): 本地回环连接 -> 校验/授予权限 -> 校验/激活无障碍 -> 校验系统设置 -> 纠正固定端口 -> 纠正授权超时
 * 分支二 (adbd 未启动): 具备权限+WiFi开启 -> 启动无线调试+连接+纠正；具备 Root -> Root 启动+纠正；无权限 -> 引导用户
 */
object StartupCoordinator {

    private val mutex = Mutex()

    interface Listener {
        fun onStatusToast(resId: Int, vararg args: Any)
        fun onGuidePermissionNeeded()
        fun onStateChanged()
    }

    suspend fun executeStartupFlow(context: Context, listener: Listener? = null) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) {
            return@withContext
        }

        try {
            val configuredPort = AppPreferences.getFixedPort(context)

            // 探针检测 adbd 是否已在运行并监听端口
            val activePortInfo = AdbController.getActivePortInfo(context, configuredPort)
            var activePort: Int? = when {
                activePortInfo.isActive && activePortInfo.port > 0 -> activePortInfo.port
                AdbController.isPortListening(configuredPort) -> configuredPort
                configuredPort != AdbController.DEFAULT_PORT && AdbController.isPortListening(AdbController.DEFAULT_PORT) -> AdbController.DEFAULT_PORT
                else -> null
            }

            if (activePort == null) {
                val tlsPort = AdbController.getDynamicTlsPort()
                if (tlsPort != null && tlsPort > 0 && AdbController.isPortListening(tlsPort)) {
                    activePort = tlsPort
                }
            }

            if (activePort != null) {
                // ====================================================
                // 分支一：adbd 已启动 (Branch 1)
                // ====================================================
                executeBranchAdbdRunning(context, activePort, configuredPort, listener)
            } else {
                // ====================================================
                // 分支二：adbd 未启动 (Branch 2)
                // ====================================================
                executeBranchAdbdNotRunning(context, configuredPort, listener)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
        } finally {
            mutex.unlock()
            withContext(NonCancellable + Dispatchers.Main) {
                listener?.onStateChanged()
            }
        }
    }

    /**
     * 分支一：adbd 活跃状态下的自动化决策链
     */
    private suspend fun executeBranchAdbdRunning(
        context: Context,
        activePort: Int,
        fixedPort: Int,
        listener: Listener?
    ) {
        val keyPair = try {
            AdbController.getAdbKeyPair(context)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        } ?: return

        var client: Dadb? = null
        try {
            WifiAdbAccessibilityService.grantAuthPermit(10000L)
            client = Dadb.create(
                host = "127.0.0.1",
                port = activePort,
                keyPair = keyPair,
                connectTimeout = 3000,
                socketTimeout = 5000
            )
        } catch (_: Throwable) {
            // 连接失败或无法握手
        }

        if (client != null) {
            client.use { dadb ->
                val pkg = context.packageName

                // 1. Check WRITE_SECURE_SETTINGS
                val hasSecure = AdbController.hasWriteSecureSettings(context)
                if (!hasSecure) {
                    val isRoot = AdbController.isRootAvailable()
                    var permGranted = false
                    if (isRoot) {
                        permGranted = AdbController.grantWriteSecureSettingsWithRoot(context)
                    }
                    if (!permGranted) {
                        val grantRes = runCatching {
                            dadb.shell("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
                        }.getOrNull()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            runCatching { dadb.shell("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow") }
                        }
                        permGranted = (grantRes?.exitCode == 0) || AdbController.hasWriteSecureSettings(context)
                    }
                    if (!permGranted) {
                        listener?.onStatusToast(R.string.toast_perm_grant_failed)
                    }
                }

                // 2. Check Accessibility status
                val isA11yActive = WifiAdbAccessibilityService.isServiceEnabled(context)
                if (!isA11yActive) {
                    var a11yActivated = false
                    if (AppPreferences.isStartupRootActivateA11y(context) && AdbController.isRootAvailable()) {
                        a11yActivated = AdbController.setAccessibilityServiceSilent(context, true)
                    }
                    if (!a11yActivated && AppPreferences.isStartupAdbActivateA11y(context)) {
                        val dadbA11yRes = AdbController.grantAccessibilityViaDadbShell(context, activePort)
                        a11yActivated = dadbA11yRes.isSuccess || WifiAdbAccessibilityService.isServiceEnabled(context)
                    }
                    if (!a11yActivated && (AppPreferences.isStartupRootActivateA11y(context) || AppPreferences.isStartupAdbActivateA11y(context))) {
                        listener?.onStatusToast(R.string.toast_a11y_activate_failed)
                    }
                }

                // 3. Check Wireless Debugging setting (enable if user requested startup enable)
                if (AppPreferences.isStartupEnableWirelessDebug(context)) {
                    if (!AdbController.isAdbWifiGlobalEnabled(context)) {
                        if (AppPreferences.isStartupRootEnableWirelessDebug(context) && AdbController.isRootAvailable()) {
                            ShellUtils.execRoot("settings put global adb_wifi_enabled 1")
                        } else {
                            runCatching {
                                Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
                            }
                            runCatching {
                                dadb.shell("settings put global adb_wifi_enabled 1")
                            }
                        }
                    }
                }

                // 4. Check Port != FixedPort
                var currentPort = activePort
                if (currentPort != fixedPort) {
                    var portFixed = false
                    if (AppPreferences.isStartupRootFixPort(context) && AdbController.isRootAvailable()) {
                        portFixed = AdbController.fixPort(context, fixedPort, useRoot = true, currentActivePort = currentPort)
                    } else if (AppPreferences.isStartupAdbFixPort(context)) {
                        portFixed = AdbController.fixPort(context, fixedPort, useRoot = false, currentActivePort = currentPort)
                    }
                    if (portFixed) {
                        currentPort = fixedPort
                        listener?.onStatusToast(R.string.toast_fix_port_success, fixedPort)
                    }
                }

                // 5. Check Auth Timeout
                if (AppPreferences.isStartupDisableAuthTimeout(context) || AppPreferences.isStartupRootDisableAuthTimeout(context)) {
                    val currentTimeout = AdbController.getAuthTimeout(context)
                    if (currentTimeout != 0L) {
                        val useRoot = AppPreferences.isStartupRootDisableAuthTimeout(context) && AdbController.isRootAvailable()
                        val timeoutOk = AdbController.setAuthTimeout(context, 0L, useRoot = useRoot)
                        if (timeoutOk) {
                            listener?.onStatusToast(R.string.toast_timeout_disabled)
                        }
                    }
                }
            }
        } else {
            // 若回环连接失败，但具备 Root 权限，仍可按需执行 Root 级别的端口与超时校准
            if (AdbController.isRootAvailable()) {
                if (AppPreferences.isStartupRootFixPort(context) && activePort != fixedPort) {
                    AdbController.fixPort(context, fixedPort, useRoot = true, currentActivePort = activePort)
                }
                if (AppPreferences.isStartupRootDisableAuthTimeout(context)) {
                    AdbController.setAuthTimeout(context, 0L, useRoot = true)
                }
            }
        }
    }

    /**
     * 分支二：adbd 未启动状态下的自动化决策链
     */
    private suspend fun executeBranchAdbdNotRunning(
        context: Context,
        fixedPort: Int,
        listener: Listener?
    ) {
        val hasSecure = AdbController.hasWriteSecureSettings(context)
        val hasRoot = AdbController.isRootAvailable()

        when {
            hasSecure -> {
                // 具备系统设置权限，检查 WiFi 状态
                if (NetworkUtils.isWifiConnected(context)) {
                    if (AppPreferences.isStartupEnableWirelessDebug(context)) {
                        // 启动无线调试
                        AdbController.setWirelessDebuggingEnabled(context, true, fixedPort)
                        delay(1200)

                        // 探针获取新开启的无线调试端口并进行连接与配置
                        val activeInfo = AdbController.getActivePortInfo(context, fixedPort)
                        val port = if (activeInfo.isActive && activeInfo.port > 0) activeInfo.port else fixedPort
                        if (AdbController.isPortListening(port)) {
                            WifiAdbAccessibilityService.grantAuthPermit(10000L)
                            // 连接后纠正端口与超时
                            if (port != fixedPort && (AppPreferences.isStartupRootFixPort(context) || AppPreferences.isStartupAdbFixPort(context))) {
                                AdbController.fixPort(context, fixedPort, useRoot = AppPreferences.isStartupRootFixPort(context), currentActivePort = port)
                            }
                            if (AppPreferences.isStartupDisableAuthTimeout(context) || AppPreferences.isStartupRootDisableAuthTimeout(context)) {
                                AdbController.setAuthTimeout(context, 0L, useRoot = AppPreferences.isStartupRootDisableAuthTimeout(context))
                            }
                        }
                    }
                } else {
                    // WiFi 未开启，等待网络广播通知 (MainActivity/WifiStateReceiver 会在连接后再次触发)
                }
            }

            hasRoot -> {
                // 具备 Root 权限
                if (AppPreferences.isStartupRootEnableWirelessDebug(context)) {
                    AdbController.startWirelessAdb(fixedPort)
                    delay(500)
                    if (AppPreferences.isStartupRootFixPort(context)) {
                        AdbController.fixPort(context, fixedPort, useRoot = true, currentActivePort = fixedPort)
                    }
                    if (AppPreferences.isStartupRootDisableAuthTimeout(context)) {
                        AdbController.setAuthTimeout(context, 0L, useRoot = true)
                    }
                }
            }

            else -> {
                // 无系统设置权限且无 Root，引导用户提权
                withContext(Dispatchers.Main) {
                    listener?.onGuidePermissionNeeded()
                }
            }
        }
    }
}
