package com.xrsec.wifiadb.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

object NetworkUtils {

    /**
     * 获取设备当前的局域网 IPv4 地址。
     * 适配 Android 4.4 KitKat (API 19) 及以上版本：
     * 优先通过 NetworkInterface 枚举物理网卡（如 wlan0），备选通过 WifiManager.connectionInfo.ipAddress 解析。
     */
    @Suppress("DEPRECATION")
    fun getLocalIpAddress(context: Context): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // 优先寻找 wlan 或 eth 开头的无线/以太网接口
            val preferredInterfaces = interfaces.filter { iface ->
                val name = iface.name.lowercase(Locale.US)
                name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")
            }
            val otherInterfaces = interfaces.filterNot { preferredInterfaces.contains(it) }
            for (iface in preferredInterfaces + otherInterfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = Collections.list(iface.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress
                        if (!hostAddress.isNullOrBlank() && hostAddress != "127.0.0.1") {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // 忽略异常，降级到 WifiManager
        }

        // 降级方案：从 WifiManager 获取
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 获取当前连接的 WiFi 名称 (SSID)。
     */
    @Suppress("DEPRECATION")
    fun getWifiSsid(context: Context): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifiManager?.connectionInfo
            val ssid = info?.ssid ?: ""
            val cleanSsid = if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length >= 2) {
                ssid.substring(1, ssid.length - 1)
            } else {
                ssid
            }
            if (cleanSsid.isBlank() || cleanSsid == "<unknown ssid>") {
                ""
            } else {
                cleanSsid
            }
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * 判断当前是否已连接 WiFi 或局域网。
     */
    @Suppress("DEPRECATION")
    fun isWifiConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            } else {
                val info = cm.activeNetworkInfo ?: return false
                info.isConnected && (info.type == ConnectivityManager.TYPE_WIFI || info.type == ConnectivityManager.TYPE_ETHERNET)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 判断当前是否已连接网络。
     */
    @Suppress("DEPRECATION")
    fun isNetworkConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetworkInfo
            activeNetwork != null && activeNetwork.isConnected
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 格式化 ADB 连接命令。
     */
    fun formatAdbConnectCommand(ip: String, port: Int = 5555): String {
        return "adb connect $ip:$port"
    }
}
