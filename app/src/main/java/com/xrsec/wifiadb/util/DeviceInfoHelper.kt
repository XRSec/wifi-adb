package com.xrsec.wifiadb.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.xrsec.wifiadb.core.ShellUtils

object DeviceInfoHelper {

    data class DeviceInfo(
        val manufacturer: String,
        val model: String,
        val serialNumber: String,
        val androidRelease: String,
        val sdkInt: Int,
        val cpuAbi: String
    ) {
        val modelDisplayName: String
            get() = if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model"
            }

        val systemVersionName: String
            get() = "Android $androidRelease (API $sdkInt)"

        fun getFormattedSummary(): String {
            return """
                Device: $modelDisplayName
                Serial: $serialNumber
                System: $systemVersionName
                ABI: $cpuAbi
            """.trimIndent()
        }
    }

    /**
     * 获取设备序列号：
     * 1. 优先读取系统属性 getprop ro.serialno
     * 2. 兜底读取 Build.SERIAL
     * 3. 兜底读取 Settings.Secure.ANDROID_ID
     */
    @SuppressLint("HardwareIds")
    @Suppress("DEPRECATION")
    fun getSerialNumber(context: Context): String {
        // 1. getprop ro.serialno
        val propRes = runCatching {
            ShellUtils.exec("getprop ro.serialno").stdout.trim()
        }.getOrNull()
        if (!propRes.isNullOrBlank() && !propRes.equals("unknown", ignoreCase = true)) {
            return propRes
        }

        // 2. Build.SERIAL (Android 8.0 之前可直接读取)
        val serial = runCatching { Build.SERIAL }.getOrNull()
        if (!serial.isNullOrBlank() && !serial.equals("unknown", ignoreCase = true) && serial != Build.UNKNOWN) {
            return serial
        }

        // 3. Settings.Secure.ANDROID_ID 兜底
        return runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
    }

    /**
     * 获取完整设备硬件与系统信息
     */
    fun getDeviceInfo(context: Context): DeviceInfo {
        val abi = if (Build.SUPPORTED_ABIS.isNotEmpty()) {
            Build.SUPPORTED_ABIS[0]
        } else {
            @Suppress("DEPRECATION")
            Build.CPU_ABI ?: "unknown"
        }

        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }

        return DeviceInfo(
            manufacturer = manufacturer,
            model = Build.MODEL.orEmpty(),
            serialNumber = getSerialNumber(context),
            androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "Unknown" },
            sdkInt = Build.VERSION.SDK_INT,
            cpuAbi = abi
        )
    }
}
