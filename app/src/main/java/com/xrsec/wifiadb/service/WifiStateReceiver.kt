package com.xrsec.wifiadb.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xrsec.wifiadb.util.NetworkUtils

class WifiStateReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_NETWORK_STATE_CHANGED = "com.xrsec.wifiadb.NETWORK_STATE_CHANGED"
        const val EXTRA_IP_ADDRESS = "extra_ip_address"
        const val EXTRA_WIFI_SSID = "extra_wifi_ssid"
        const val EXTRA_IS_CONNECTED = "extra_is_connected"

        var listener: ((ip: String?, ssid: String, isConnected: Boolean) -> Unit)? = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        val isConnected = NetworkUtils.isNetworkConnected(context)
        val ip = NetworkUtils.getLocalIpAddress(context)
        val ssid = NetworkUtils.getWifiSsid(context)

        // 通知内存中的注册回调 (如处于活跃状态的 MainActivity)
        listener?.invoke(ip, ssid, isConnected)

        // 同时发送内部广播，确保无论前台或后台均可感知
        val updateIntent = Intent(ACTION_NETWORK_STATE_CHANGED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_IP_ADDRESS, ip)
            putExtra(EXTRA_WIFI_SSID, ssid)
            putExtra(EXTRA_IS_CONNECTED, isConnected)
        }
        context.sendBroadcast(updateIntent)

        // 通知前台守护服务刷新通知文案
        val serviceIntent = Intent(context, AdbWirelessService::class.java).apply {
            action = AdbWirelessService.ACTION_UPDATE_NOTIFICATION
        }
        try {
            context.startService(serviceIntent)
        } catch (_: Throwable) {}
    }
}
