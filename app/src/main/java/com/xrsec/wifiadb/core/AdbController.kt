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

        // 在同一个 Root 管道中一揽子全部搞定：赋权 + 解除 Android 13+ 受限设置 + 静默激活无障碍服务
        val commandsList = mutableListOf(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            commandsList.add("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
        }
        commandsList.add("settings put secure enabled_accessibility_services \"$newServices\"")
        commandsList.add("settings put secure accessibility_enabled 1")
        val commands = commandsList.joinToString(" && ")

        val res = ShellUtils.execRoot(commands)
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
     * 一键本地闭环提权：
     * 利用 dadb 连接本地 127.0.0.1:port 发起 ADB 握手，触发系统授权弹窗，
     * 连通后在同一会话中一网打尽：
     * 1. pm grant 授予 WRITE_SECURE_SETTINGS 权限；
     * 2. appops set 解除 Android 13+ 受限设置；
     * 3. 自动将无障碍服务写入 enabled_accessibility_services 并静默激活！
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

        // 4. 未启动
        ActivePortInfo(isActive = false, port = configuredPort, isSystemWireless = false)
    }

    /**
     * 判断无线调试是否处于活跃状态：
     * 判定准则：系统全局设置 adb_wifi_enabled == 1 或本地 Loopback Socket 探针连通。
     */
    suspend fun isAdbWirelessActive(context: Context, port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        isAdbWifiGlobalEnabled(context) || isPortListening(port)
    }

    suspend fun isAdbWirelessActive(port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        isPortListening(port)
    }

    /**
     * 真实控制系统无线调试状态：
     * 1. 尝试通过 WRITE_SECURE_SETTINGS 写入 Settings.Global.adb_wifi_enabled (Android 11+ 系统真实开关)
     * 2. 若具备 Root，同时配合 setprop service.adb.tcp.port 并重启 adbd
     * 3. 联动 AdbWirelessService 前台通知服务
     */
    suspend fun setWirelessDebuggingEnabled(context: Context, enable: Boolean, port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        var anyActionTaken = false

        // 1. 系统设置真实开关 (需要 WRITE_SECURE_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val globalVal = if (enable) 1 else 0
                val success = Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", globalVal)
                if (success) anyActionTaken = true
            } catch (_: Throwable) {}
        }

        // 2. 若具备 Root，同时执行 setprop 与 adbd 重启
        if (ShellUtils.isRootAvailable()) {
            val rootSuccess = if (enable) {
                startWirelessAdb(port)
            } else {
                stopWirelessAdb(port)
            }
            if (rootSuccess) anyActionTaken = true
        }

        // 3. 联动 AdbWirelessService 前台服务
        try {
            AdbWirelessService.start(context, AdbWirelessService.ACTION_UPDATE_NOTIFICATION)
        } catch (_: Throwable) {}

        delay(300)
        isAdbWirelessActive(context, port)
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
