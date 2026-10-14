package com.xrsec.wifiadb.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object AdbController {

    const val DEFAULT_PORT = 5555

    enum class PermissionType {
        NONE,
        SECURE_SETTINGS,
        ROOT
    }

    /**
     * 判定当前系统提权状态：
     * 1. 优先判定 Root
     * 2. 其次判定 WRITE_SECURE_SETTINGS
     * 3. 均无则返回 NONE
     */
    fun checkSystemPermission(context: Context): PermissionType {
        if (ShellUtils.isRootAvailable()) {
            return PermissionType.ROOT
        }
        val hasSecure = context.checkCallingOrSelfPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasSecure) {
            return PermissionType.SECURE_SETTINGS
        }
        return PermissionType.NONE
    }

    /**
     * 尝试获取 Root 权限，并在成功后自动为本 App 授予 WRITE_SECURE_SETTINGS 权限。
     */
    suspend fun requestRootPermission(context: Context): Boolean = withContext(Dispatchers.IO) {
        val result = ShellUtils.execRoot("id")
        if (result.isSuccess && result.stdout.contains("uid=0")) {
            val pkg = context.packageName
            ShellUtils.execRoot("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
            true
        } else {
            false
        }
    }

    /**
     * 静默开启或关闭无障碍服务。
     * 采用 ComponentName 对象级反序列化比对，兼容短名称和完整名称。
     * 当关闭无障碍且无其他已启用服务时，将 accessibility_enabled 置 0，杜绝僵尸状态。
     */
    suspend fun setAccessibilityServiceSilent(context: Context, enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val targetComponent = ComponentName(context, "${context.packageName}.service.WifiAdbAccessibilityService")
        val targetFlattened = targetComponent.flattenToString()

        val currentServicesStr = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val currentComponents = currentServicesStr.split(":")
            .filter { it.isNotBlank() }
            .mapNotNull { ComponentName.unflattenFromString(it) }

        val filteredFlattened = currentComponents.filterNot {
            it.packageName == targetComponent.packageName && it.className == targetComponent.className
        }.map { it.flattenToString() }.toMutableList()

        if (enable) {
            filteredFlattened.add(targetFlattened)
        }

        val newServices = filteredFlattened.joinToString(":")
        val accessibilityEnabledVal = if (newServices.isNotBlank()) 1 else 0

        // 尝试方式 1: Root Shell 执行
        if (ShellUtils.isRootAvailable()) {
            val cmd = "settings put secure enabled_accessibility_services \"$newServices\" && settings put secure accessibility_enabled $accessibilityEnabledVal"
            val res = ShellUtils.execRoot(cmd)
            if (res.isSuccess) {
                delay(300)
                return@withContext true
            }
        }

        // 尝试方式 2: WRITE_SECURE_SETTINGS 权限写入
        try {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newServices
            )
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                accessibilityEnabledVal
            )
            delay(300)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 检测本地指定端口（默认 5555）是否已在监听中。
     * 本检测无需 Root 权限，直接尝试与 127.0.0.1 建立 Socket 连接。
     * 严禁在主线程调用，必须在 IO 调度器中执行。
     */
    fun isPortListening(port: Int = DEFAULT_PORT, timeoutMs: Int = 800): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        } catch (_: Throwable) {
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 读取系统属性 service.adb.tcp.port 的当前值。
     */
    fun getAdbTcpPort(): Int {
        val res = ShellUtils.exec("getprop service.adb.tcp.port")
        val output = res.stdout.trim()
        return output.toIntOrNull() ?: -1
    }

    /**
     * 判断无线调试是否处于活跃状态。
     * 按照安全与严谨性规范：以实际本地 Loopback Socket 探针连通性作为核心判定准则。
     */
    suspend fun isAdbWirelessActive(port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        isPortListening(port)
    }

    /**
     * 通过 Root 权限开启无线调试 (支持自定义端口)。
     * 使用 && 串联原子指令：
     *   setprop service.adb.tcp.port $port && stop adbd && start adbd
     */
    suspend fun startWirelessAdb(port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        val command = "setprop service.adb.tcp.port $port && stop adbd && start adbd"
        val result = ShellUtils.execRoot(command)
        if (!result.isSuccess) {
            return@withContext false
        }

        // 轮询等待端口启动，最多等待 3 秒 (6 * 500ms)
        for (i in 0 until 6) {
            delay(500)
            if (isPortListening(port)) {
                return@withContext true
            }
        }
        isPortListening(port)
    }

    /**
     * 通过 Root 权限关闭无线调试，回退到 USB 调试模式 (-1)。
     * 探针针对目标端口轮询检测，确保自定义端口状态一致。
     */
    suspend fun stopWirelessAdb(port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        val command = "setprop service.adb.tcp.port -1 && stop adbd && start adbd"
        val result = ShellUtils.execRoot(command)
        if (!result.isSuccess) {
            return@withContext false
        }

        // 轮询等待指定端口关闭，最多等待 2 秒 (4 * 500ms)
        for (i in 0 until 4) {
            delay(500)
            if (!isPortListening(port)) {
                return@withContext true
            }
        }
        !isPortListening(port)
    }
}
