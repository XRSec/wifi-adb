package com.xrsec.wifiadb.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xrsec.wifiadb.service.AdbWirelessService
import com.xrsec.wifiadb.util.AppPreferences

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        if (AppPreferences.isBootStartEnabled(context)) {
            AdbWirelessService.start(context, AdbWirelessService.ACTION_START_WIRELESS)
        }
    }
}
