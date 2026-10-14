package com.xrsec.wifiadb.core

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.xrsec.wifiadb.service.AdbWirelessService
import com.xrsec.wifiadb.util.AppPreferences
import com.xrsec.wifiadb.util.KeyCryptoUtil
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

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
        if (hasWriteSecureSettings(context)) {
            return PermissionType.SECURE_SETTINGS
        }
        return PermissionType.NONE
    }

    /**
     * 明确判定本应用是否具备 WRITE_SECURE_SETTINGS 权限
     */
    fun hasWriteSecureSettings(context: Context): Boolean {
        return context.checkCallingOrSelfPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 判断当前系统是否具备 Root 权限
     */
    fun isRootAvailable(): Boolean {
        return ShellUtils.isRootAvailable()
    }

    /**
     * 通过 Root 权限为本 App 授予 WRITE_SECURE_SETTINGS 权限，解除受限设置，并一揽子静默启用无障碍服务。
     */
    suspend fun grantWriteSecureSettingsWithRoot(context: Context): Boolean = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val targetComponent = ComponentName(context, "$pkg.service.WifiAdbAccessibilityService")
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
        filteredFlattened.add(targetFlattened)

        val newServices = filteredFlattened.joinToString(":")

        // 在同一个 Root 管道中一揽子全部搞定：赋权 + 解除 Android 13+ 受限设置 + 静默激活无障碍服务 + 永久禁用授权超时
        val commandsList = mutableListOf(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            commandsList.add("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
        }
        commandsList.add("settings put secure enabled_accessibility_services \"$newServices\"")
        commandsList.add("settings put secure accessibility_enabled 1")
        commandsList.add("settings put global adb_allowed_connection_time 0")
        val commands = commandsList.joinToString(" && ")

        val res = ShellUtils.execRoot(commands)
        disableAdbAuthTimeout(context)
        if (res.isSuccess || hasWriteSecureSettings(context)) {
            delay(300)
            hasWriteSecureSettings(context)
        } else {
            false
        }
    }

    /**
     * 尝试获取 Root 权限，并在成功后自动为本 App 授予 WRITE_SECURE_SETTINGS 权限并激活无障碍服务。
     */
    suspend fun requestRootPermission(context: Context): Boolean = withContext(Dispatchers.IO) {
        val result = ShellUtils.execRoot("id")
        if (result.isSuccess && result.stdout.contains("uid=0")) {
            grantWriteSecureSettingsWithRoot(context)
        } else {
            false
        }
    }

    /**
     * 禁用 ADB 授权超时 (adb_allowed_connection_time = 0)，
     * 关闭 Android 11+ 默认的 7 天无连接自动撤销 ADB 授权机制，使设备上的授权永久生效。
     */
    fun disableAdbAuthTimeout(context: Context): Boolean {
        var success = false
        try {
            success = Settings.Global.putLong(context.contentResolver, "adb_allowed_connection_time", 0L)
        } catch (_: Throwable) {}

        try {
            val res = ShellUtils.exec("settings put global adb_allowed_connection_time 0")
            if (res.isSuccess) success = true
        } catch (_: Throwable) {}

        return success
    }

    /**
     * 一键本地闭环提权：
     * 利用 dadb 连接本地 127.0.0.1:port 发起 ADB 握手，触发系统授权弹窗，
     * 连通后在同一会话中一网打尽：
     * 1. pm grant 授予 WRITE_SECURE_SETTINGS 权限；
     * 2. appops set 解除 Android 13+ 受限设置；
     * 3. 自动将无障碍服务写入 enabled_accessibility_services 并静默激活；
     * 4. 自动将 adb_allowed_connection_time 设为 0（永久不过期）；
     * 5. 若配置了非默认静态端口，自动切换/固定端口为用户常用端口！
     */
    suspend fun grantWriteSecureSettingsViaDadb(context: Context, port: Int = DEFAULT_PORT): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val keyPair = try {
                getAdbKeyPair(context)
            } catch (e: Throwable) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                regenerateAdbKey(context)
                getAdbKeyPair(context)
            }

            // 超时配置：connectTimeout 5s，socketTimeout 30s 以留足系统弹窗识别与点击时间
            val dadb = Dadb.create(
                host = "127.0.0.1",
                port = port,
                keyPair = keyPair,
                connectTimeout = 5000,
                socketTimeout = 30000
            )
            dadb.use { client ->
                val pkg = context.packageName
                // 1. 授予系统设置写入权限
                val grantResponse = client.shell("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")

                // 2. 解除 Android 13+ 受限设置阻断
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { client.shell("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow") }
                }

                // 3. 在同一会话中一揽子静默开启无障碍服务，实现 0 手动点击
                val targetComponent = ComponentName(context, "$pkg.service.WifiAdbAccessibilityService")
                val targetFlattened = targetComponent.flattenToString()
                val getRes = client.shell("settings get secure enabled_accessibility_services")
                val currentServicesStr = getRes.allOutput.trim().takeIf { it != "null" } ?: ""

                val currentComponents = currentServicesStr.split(":")
                    .filter { it.isNotBlank() }
                    .mapNotNull { ComponentName.unflattenFromString(it) }

                val filteredFlattened = currentComponents.filterNot {
                    it.packageName == targetComponent.packageName && it.className == targetComponent.className
                }.map { it.flattenToString() }.toMutableList()
                filteredFlattened.add(targetFlattened)

                val newServices = filteredFlattened.joinToString(":")

                client.shell("settings put secure enabled_accessibility_services \"$newServices\"")
                client.shell("settings put secure accessibility_enabled 1")

                // 4. 自动关闭 7 天无连接撤销授权限制 (设置 adb_allowed_connection_time = 0)
                client.shell("settings put global adb_allowed_connection_time 0")
                disableAdbAuthTimeout(context)

                // 5. 连接后自动切换/固定端口至用户配置端口 (默认 5555，免 Root 原生 tcpip 链路)
                val configuredPort = AppPreferences.getPort(context)
                if (configuredPort > 0) {
                    runCatching {
                        client.tcpip(configuredPort)
                    }
                }

                // 同步利用 ContentResolver 刷新，确保系统即时反映状态
                runCatching {
                    Settings.Secure.putString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        newServices
                    )
                    Settings.Secure.putInt(
                        context.contentResolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1
                    )
                }

                if (grantResponse.exitCode == 0 || hasWriteSecureSettings(context)) {
                    Result.success(Unit)
                } else {
                    val errMsg = grantResponse.allOutput.ifBlank { grantResponse.errorOutput }.ifBlank { "Exit code ${grantResponse.exitCode}" }
                    Result.failure(Exception("pm grant failed: $errMsg"))
                }
            }
        } catch (e: Throwable) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * 专属实体按钮操作：通过 dadb Shell（或 Root）会话一键激活无障碍服务。
     * 在后台直接启动 dadb/Root 执行：
     *   settings put secure enabled_accessibility_services ...
     *   settings put secure accessibility_enabled 1
     *   appops set com.xrsec.wifiadb ACCESS_RESTRICTED_SETTINGS allow
     *   settings put global adb_allowed_connection_time 0
     */
    suspend fun grantAccessibilityViaDadbShell(context: Context, port: Int = DEFAULT_PORT): Result<Unit> = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val targetComponent = ComponentName(context, "$pkg.service.WifiAdbAccessibilityService")
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
        filteredFlattened.add(targetFlattened)

        val newServices = filteredFlattened.joinToString(":")

        // 优先使用 Root 极速通道
        if (ShellUtils.isRootAvailable()) {
            val cmdList = mutableListOf(
                "settings put secure enabled_accessibility_services \"$newServices\"",
                "settings put secure accessibility_enabled 1",
                "settings put global adb_allowed_connection_time 0"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cmdList.add("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
            }
            val res = ShellUtils.execRoot(cmdList.joinToString(" && "))
            if (res.isSuccess) {
                disableAdbAuthTimeout(context)
                delay(300)
                return@withContext Result.success(Unit)
            }
        }

        // dadb 通道
        try {
            val keyPair = try {
                getAdbKeyPair(context)
            } catch (e: Throwable) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                regenerateAdbKey(context)
                getAdbKeyPair(context)
            }

            val dadb = Dadb.create(
                host = "127.0.0.1",
                port = port,
                keyPair = keyPair,
                connectTimeout = 5000,
                socketTimeout = 30000
            )
            dadb.use { client ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { client.shell("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow") }
                }
                client.shell("settings put secure enabled_accessibility_services \"$newServices\"")
                client.shell("settings put secure accessibility_enabled 1")
                client.shell("settings put global adb_allowed_connection_time 0")

                // 尝试切换/固定端口为用户设定的常用端口 (免 Root 原生 tcpip 链路)
                val configuredPort = AppPreferences.getPort(context)
                if (configuredPort > 0 && configuredPort != port) {
                    runCatching {
                        client.tcpip(configuredPort)
                    }
                }

                // 同步通过 ContentResolver 刷新
                runCatching {
                    Settings.Secure.putString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        newServices
                    )
                    Settings.Secure.putInt(
                        context.contentResolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1
                    )
                }
                disableAdbAuthTimeout(context)
                delay(300)
                Result.success(Unit)
            }
        } catch (e: Throwable) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * 将系统动态 TLS 端口锁定/切换为用户配置的目标静态端口（默认 5555）。
     * 免 Root 核心链路：直接通过 ADB 协议调用 client.tcpip(targetPort)，adbd 守护进程将在特权上下文原生重载监听端口。
     * 若具备 Root 权限，亦保留 setprop 作为极速备用通道。
     */
    suspend fun fixAdbTcpPort(context: Context, targetPort: Int = DEFAULT_PORT, connectPort: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        if (ShellUtils.isRootAvailable()) {
            val cmd = "setprop service.adb.tcp.port $targetPort && stop adbd && start adbd"
            val res = ShellUtils.execRoot(cmd)
            if (res.isSuccess) {
                delay(500)
                if (isPortListening(targetPort)) return@withContext true
            }
        }

        try {
            val keyPair = getAdbKeyPair(context)
            Dadb.create(
                host = "127.0.0.1",
                port = connectPort,
                keyPair = keyPair,
                connectTimeout = 3000,
                socketTimeout = 5000
            ).use { client ->
                // adbd 收到 tcpip 协议指令后会返回确认信息并重启服务，重置当前 socket 链路
                runCatching { client.tcpip(targetPort) }
            }
        } catch (_: Throwable) {
            // 忽略连接阶段可能因 adbd 重启导致的异常
        }

        // 轮询等待目标端口监听生效（最多等待 3 秒）
        for (i in 1..6) {
            delay(500)
            if (isPortListening(targetPort)) {
                return@withContext true
            }
        }
        false
    }

    /**
     * 打开系统无线调试设置页面（Android 11+ / API 30+），低版本或失败时降级跳转至开发者选项
     */
    fun openWirelessDebuggingSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
                if (context !is android.app.Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {}
        }
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
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
     * 读取 Android 11+ 系统无线调试动态 TLS 端口 (service.adb.tls.port)
     */
    fun getDynamicTlsPort(): Int? {
        val prop = ShellUtils.exec("getprop service.adb.tls.port").stdout.trim()
        val parsed = prop.toIntOrNull()?.takeIf { it > 0 }
        if (parsed != null) return parsed

        return runCatching {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java, String::class.java)
            val value = getMethod.invoke(null, "service.adb.tls.port", "") as String
            value.trim().toIntOrNull()?.takeIf { it > 0 }
        }.getOrNull()
    }

    fun getAdbTlsPort(): Int {
        return getDynamicTlsPort() ?: -1
    }

    /**
     * 判断系统全局设置中的无线调试 (adb_wifi_enabled) 是否已启用 (Android 11+ / API 30+)
     */
    fun isAdbWifiGlobalEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (_: Throwable) {
            false
        }
    }

    data class ActivePortInfo(
        val isActive: Boolean,
        val port: Int,
        val isSystemWireless: Boolean
    )

    /**
     * 获取当前无线调试活跃状态与实际生效端口：
     * 1. 优先检测静态 TCP 监听端口（用户配置端口或 5555）
     * 2. 其次检测 Android 11+ 系统无线调试动态 TLS 端口
     * 3. 区分静态端口与系统无线调试动态端口，确保展示与连接真实精准
     */
    suspend fun getActivePortInfo(context: Context, configuredPort: Int = DEFAULT_PORT): ActivePortInfo = withContext(Dispatchers.IO) {
        // 1. 若配置的静态 TCP 端口在监听，判定为静态端口活跃
        if (isPortListening(configuredPort)) {
            return@withContext ActivePortInfo(isActive = true, port = configuredPort, isSystemWireless = false)
        }

        // 2. 检查 Android 11+ 系统全局无线调试开关
        if (isAdbWifiGlobalEnabled(context)) {
            val tlsPort = getDynamicTlsPort()
            if (tlsPort != null && tlsPort > 0) {
                return@withContext ActivePortInfo(isActive = true, port = tlsPort, isSystemWireless = true)
            }
            return@withContext ActivePortInfo(isActive = true, port = -1, isSystemWireless = true)
        }

        // 3. 兜底检测标准 5555 端口
        if (configuredPort != DEFAULT_PORT && isPortListening(DEFAULT_PORT)) {
            return@withContext ActivePortInfo(isActive = true, port = DEFAULT_PORT, isSystemWireless = false)
        }

        // 4. 兜底检测系统动态 TLS 端口是否仍在监听
        val tlsPort = getDynamicTlsPort()
        if (tlsPort != null && tlsPort > 0 && isPortListening(tlsPort)) {
            return@withContext ActivePortInfo(isActive = true, port = tlsPort, isSystemWireless = true)
        }

        // 5. 未启动
        ActivePortInfo(isActive = false, port = configuredPort, isSystemWireless = false)
    }

    /**
     * 判断无线调试是否处于活跃状态：
     * 判定准则：基于 getActivePortInfo 综合判定（系统全局设置 adb_wifi_enabled == 1 或本地 Socket 监听活跃）。
     */
    suspend fun isAdbWirelessActive(context: Context, port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        getActivePortInfo(context, port).isActive
    }

    suspend fun isAdbWirelessActive(port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        isPortListening(port)
    }

    /**
     * 真实控制系统无线调试状态：
     * 1. 当 enable == true 时：
     *    - 尝试通过 WRITE_SECURE_SETTINGS 写入 Settings.Global.adb_wifi_enabled = 1
     *    - 若具备 Root，同时执行 setprop service.adb.tcp.port 并重启 adbd
     * 2. 当 enable == false 时：
     *    - 尝试通过 WRITE_SECURE_SETTINGS 写入 Settings.Global.adb_wifi_enabled = 0
     *    - 若具备 Root，同时执行 settings put global adb_wifi_enabled 0、setprop -1 与重启 adbd
     *    - 若尚未关闭且 dadb 可连通（通过活跃端口与本地 ADB 密钥），通过 dadb shell 执行关闭
     * 3. 联动 AdbWirelessService 前台服务
     * 4. 轮询最多 1.5 秒验证是否达成目标状态 (isAdbWirelessActive == enable)，返回最终布尔结果
     */
    suspend fun setWirelessDebuggingEnabled(context: Context, enable: Boolean, port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        if (enable) {
            // 1. 系统设置真实开关 (需要 WRITE_SECURE_SETTINGS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
                } catch (_: Throwable) {}
            }

            // 2. 若具备 Root，同时执行 setprop 与 adbd 重启
            if (ShellUtils.isRootAvailable()) {
                startWirelessAdb(port)
            }
        } else {
            // 1. 系统设置真实开关 (若具备 WRITE_SECURE_SETTINGS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
                } catch (_: Throwable) {}
            }

            // 2. 若具备 Root，同时执行 settings put global adb_wifi_enabled 0、setprop 与 adbd 重启
            if (ShellUtils.isRootAvailable()) {
                stopWirelessAdb(port)
            }

            // 3. 若尚未关闭且 dadb 可连通（通过活跃端口与本地 ADB 密钥），通过 dadb shell 执行关闭
            if (isAdbWirelessActive(context, port)) {
                val candidates = mutableListOf<Int>()
                if (isPortListening(port)) candidates.add(port)
                val dynPort = getDynamicTlsPort()
                if (dynPort != null && dynPort > 0 && isPortListening(dynPort)) candidates.add(dynPort)
                if (port != DEFAULT_PORT && isPortListening(DEFAULT_PORT)) candidates.add(DEFAULT_PORT)

                if (candidates.isNotEmpty()) {
                    try {
                        val keyPair = try {
                            getAdbKeyPair(context)
                        } catch (e: Throwable) {
                            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                            null
                        }
                        if (keyPair != null) {
                            for (candidatePort in candidates.distinct()) {
                                try {
                                    val dadb = Dadb.create(
                                        host = "127.0.0.1",
                                        port = candidatePort,
                                        keyPair = keyPair,
                                        connectTimeout = 1000,
                                        socketTimeout = 2000
                                    )
                                    dadb.use { client ->
                                        client.shell("settings put global adb_wifi_enabled 0")
                                        runCatching { client.shell("setprop service.adb.tcp.port -1") }
                                    }
                                    break
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
        }

        // 联动 AdbWirelessService 前台服务
        try {
            AdbWirelessService.start(context, AdbWirelessService.ACTION_UPDATE_NOTIFICATION)
        } catch (_: Throwable) {}

        // 轮询验证目标状态是否达成，最多 1.5 秒 (5 * 300ms)
        val targetState = enable
        var matched = false
        for (i in 0 until 5) {
            delay(300)
            if (isAdbWirelessActive(context, port) == targetState) {
                matched = true
                break
            }
        }
        matched
    }

    /**
     * 获取解密后的 AdbKeyPair 实例。
     * 若私钥已加密，自动通过 AppPreferences 中的 Passphrase 进行解密还原并安全加载。
     */
    fun getAdbKeyPair(context: Context): AdbKeyPair {
        val privFile = File(context.filesDir, "adbkey")
        val pubFile = File(context.filesDir, "adbkey.pub")
        val passphrase = AppPreferences.getAdbKeyPassphrase(context)

        if (!privFile.exists() || !pubFile.exists() || privFile.length() == 0L || pubFile.length() == 0L) {
            regenerateAdbKey(context)
        }

        val privBytes = privFile.readBytes()
        val plainPrivBytes = if (KeyCryptoUtil.isEncrypted(privBytes)) {
            KeyCryptoUtil.decrypt(privBytes, passphrase)
        } else {
            privBytes
        }

        val tempKeyFile = File.createTempFile("adbkey_", ".tmp", context.cacheDir)
        return try {
            tempKeyFile.writeBytes(plainPrivBytes)
            AdbKeyPair.read(tempKeyFile, pubFile)
        } finally {
            runCatching {
                val wipe = ByteArray(tempKeyFile.length().toInt())
                SecureRandom().nextBytes(wipe)
                tempKeyFile.writeBytes(wipe)
                tempKeyFile.delete()
            }
        }
    }

    /**
     * 获取当前 ADB 公钥 SHA-256 指纹
     */
    fun getAdbPublicKeyFingerprint(context: Context): String {
        return runCatching {
            val pubFile = File(context.filesDir, "adbkey.pub")
            val privFile = File(context.filesDir, "adbkey")
            if (!pubFile.exists() || !privFile.exists() || pubFile.length() == 0L || privFile.length() == 0L) {
                regenerateAdbKey(context)
            }
            val keyPair = getAdbKeyPair(context)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(keyPair.adbPublicKey())
            digest.joinToString(":") { "%02X".format(it) }
        }.getOrDefault("SHA256:--:--:--")
    }

    /**
     * 重新生成本地 ADB 密钥对并返回新的指纹。
     * 若已配置 Passphrase，落盘私钥将自动完成 AES-256-GCM 加密保护。
     */
    fun regenerateAdbKey(context: Context): String {
        val privFile = File(context.filesDir, "adbkey")
        val pubFile = File(context.filesDir, "adbkey.pub")
        val passphrase = AppPreferences.getAdbKeyPassphrase(context)

        privFile.delete()
        pubFile.delete()

        val tempFile = File.createTempFile("gen_adbkey_", ".tmp", context.cacheDir)
        try {
            AdbKeyPair.generate(tempFile, pubFile, "app@wifi-adb")
            val rawPrivBytes = tempFile.readBytes()
            val finalBytes = if (passphrase.isNotBlank()) {
                KeyCryptoUtil.encrypt(rawPrivBytes, passphrase)
            } else {
                rawPrivBytes
            }
            privFile.writeBytes(finalBytes)
        } finally {
            runCatching {
                val wipe = ByteArray(tempFile.length().toInt())
                SecureRandom().nextBytes(wipe)
                tempFile.writeBytes(wipe)
                tempFile.delete()
            }
        }
        return getAdbPublicKeyFingerprint(context)
    }

    /**
     * 更新 ADB 密钥保护密码：
     * 读取现有密钥并使用新密码重新加密落盘，若新密码为空则以明文直接存储。
     */
    fun updateKeyPassphrase(context: Context, newPassphrase: String): Boolean {
        val privFile = File(context.filesDir, "adbkey")
        if (!privFile.exists() || privFile.length() == 0L) {
            AppPreferences.setAdbKeyPassphrase(context, newPassphrase)
            return true
        }

        val currentPassphrase = AppPreferences.getAdbKeyPassphrase(context)
        return try {
            val fileBytes = privFile.readBytes()
            val plainBytes = if (KeyCryptoUtil.isEncrypted(fileBytes)) {
                KeyCryptoUtil.decrypt(fileBytes, currentPassphrase)
            } else {
                fileBytes
            }
            val newBytes = if (newPassphrase.isNotBlank()) {
                KeyCryptoUtil.encrypt(plainBytes, newPassphrase)
            } else {
                plainBytes
            }
            privFile.writeBytes(newBytes)
            AppPreferences.setAdbKeyPassphrase(context, newPassphrase)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 获取当前私钥 adbkey 的明文文本内容。
     * 若私钥已加密，自动通过 AppPreferences 中的 Passphrase 进行解密还原；
     * 若解密失败（密码错误或文件损坏），返回空字符串避免显示乱码密文。
     */
    fun getAdbPrivateKeyString(context: Context): String {
        val privFile = File(context.filesDir, "adbkey")
        val pubFile = File(context.filesDir, "adbkey.pub")
        if (!privFile.exists() || !pubFile.exists() || privFile.length() == 0L || pubFile.length() == 0L) {
            regenerateAdbKey(context)
        }
        val privBytes = privFile.readBytes()
        val passphrase = AppPreferences.getAdbKeyPassphrase(context)
        val plainBytes = if (KeyCryptoUtil.isEncrypted(privBytes)) {
            runCatching { KeyCryptoUtil.decrypt(privBytes, passphrase) }.getOrNull() ?: return ""
        } else {
            privBytes
        }
        return plainBytes.toString(Charsets.UTF_8).trim()
    }

    /**
     * 获取当前公钥 adbkey.pub 的文本内容。
     */
    fun getAdbPublicKeyString(context: Context): String {
        val pubFile = File(context.filesDir, "adbkey.pub")
        val privFile = File(context.filesDir, "adbkey")
        if (!pubFile.exists() || !privFile.exists() || pubFile.length() == 0L || privFile.length() == 0L) {
            regenerateAdbKey(context)
        }
        return pubFile.readText(Charsets.UTF_8).trim()
    }

    /**
     * 保存用户在输入框中自定义/粘贴的 adbkey 与 adbkey.pub 内容。
     * 在写入磁盘真实文件之前，先将明文写入临时文件通过 AdbKeyPair.read 进行预校验；
     * 校验失败立即抛出异常并安全擦除临时文件，绝不破坏现有磁盘密钥。
     * 校验成功后执行 privFile.delete() 与 pubFile.delete() 切断符号链接，再原子落盘。
     * 若已配置 Passphrase，私钥将加密落盘；公钥以标准明文格式保存。
     * 返回更新后的公钥 SHA-256 指纹。
     */
    fun saveAdbKeyPairCustom(context: Context, privContent: String, pubContent: String): String {
        val privClean = privContent.trim()
        val pubClean = pubContent.trim()
        require(privClean.isNotBlank()) { "Private key cannot be empty" }
        require(pubClean.isNotBlank()) { "Public key cannot be empty" }

        val rawPrivBytes = privClean.toByteArray(Charsets.UTF_8)
        val tempPriv = File.createTempFile("val_priv_", ".tmp", context.cacheDir)
        val tempPub = File.createTempFile("val_pub_", ".tmp", context.cacheDir)

        try {
            tempPriv.writeBytes(rawPrivBytes)
            tempPub.writeText(pubClean + "\n", Charsets.UTF_8)
            try {
                AdbKeyPair.read(tempPriv, tempPub)
            } catch (e: Throwable) {
                throw IllegalArgumentException("Invalid ADB key format", e)
            }
        } finally {
            runCatching {
                if (tempPriv.exists()) {
                    val len = tempPriv.length().toInt()
                    if (len > 0) {
                        val wipe = ByteArray(len)
                        SecureRandom().nextBytes(wipe)
                        tempPriv.writeBytes(wipe)
                    }
                    tempPriv.delete()
                }
            }
            runCatching {
                if (tempPub.exists()) {
                    tempPub.delete()
                }
            }
        }

        val privFile = File(context.filesDir, "adbkey")
        val pubFile = File(context.filesDir, "adbkey.pub")
        val passphrase = AppPreferences.getAdbKeyPassphrase(context)

        val finalPrivBytes = if (passphrase.isNotBlank()) {
            KeyCryptoUtil.encrypt(rawPrivBytes, passphrase)
        } else {
            rawPrivBytes
        }

        privFile.delete()
        pubFile.delete()

        privFile.writeBytes(finalPrivBytes)
        pubFile.writeText(pubClean + "\n", Charsets.UTF_8)

        return getAdbPublicKeyFingerprint(context)
    }

    /**
     * 重新生成本地 ADB 密钥对并返回生成的私钥和公钥文本对。
     */
    fun generateNewAdbKeyPairStrings(context: Context): Pair<String, String> {
        regenerateAdbKey(context)
        return Pair(getAdbPrivateKeyString(context), getAdbPublicKeyString(context))
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
        val command = "settings put global adb_wifi_enabled 0 && setprop service.adb.tcp.port -1 && stop adbd && start adbd"
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
