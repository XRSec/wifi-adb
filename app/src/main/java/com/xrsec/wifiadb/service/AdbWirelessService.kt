package com.xrsec.wifiadb.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xrsec.wifiadb.MainActivity
import com.xrsec.wifiadb.R
import com.xrsec.wifiadb.core.AdbController
import com.xrsec.wifiadb.util.AppPreferences
import com.xrsec.wifiadb.util.LocaleHelper
import com.xrsec.wifiadb.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdbWirelessService : Service() {

    companion object {
        const val ACTION_START_WIRELESS = "com.xrsec.wifiadb.ACTION_START_WIRELESS"
        const val ACTION_STOP_WIRELESS = "com.xrsec.wifiadb.ACTION_STOP_WIRELESS"
        const val ACTION_UPDATE_NOTIFICATION = "com.xrsec.wifiadb.ACTION_UPDATE_NOTIFICATION"

        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "wifi_adb_foreground_channel"

        var isServiceRunning: Boolean = false
            private set

        fun start(context: Context, action: String = ACTION_UPDATE_NOTIFICATION) {
            val intent = Intent(context, AdbWirelessService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AdbWirelessService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationManager: NotificationManager? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 同步挂载基础前台通知，避免超时 ANR 崩溃
        attachForeground(buildBaseNotification(getString(R.string.notification_running_base)))

        val action = intent?.action ?: ACTION_UPDATE_NOTIFICATION
        val port = AppPreferences.getPort(this)

        serviceScope.launch {
            withContext(Dispatchers.IO) {
                when (action) {
                    ACTION_START_WIRELESS -> AdbController.setWirelessDebuggingEnabled(this@AdbWirelessService, true, port)
                    ACTION_STOP_WIRELESS -> AdbController.setWirelessDebuggingEnabled(this@AdbWirelessService, false, port)
                    else -> {}
                }
            }
            refreshNotificationContentAsync()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = getString(R.string.notification_channel_name)
            val channelDesc = getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
                description = channelDesc
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun attachForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshNotificationContentAsync() {
        val configuredPort = AppPreferences.getPort(this)
        serviceScope.launch {
            val (ip, isAdbActive, portInfo) = withContext(Dispatchers.IO) {
                val currentIp = NetworkUtils.getLocalIpAddress(this@AdbWirelessService)
                val active = AdbController.isAdbWirelessActive(this@AdbWirelessService, configuredPort)
                val info = AdbController.getActivePortInfo(this@AdbWirelessService, configuredPort)
                Triple(currentIp, active, info)
            }

            val updatedNotification = buildDetailedNotification(ip, isAdbActive, portInfo, configuredPort)
            notificationManager?.notify(NOTIFICATION_ID, updatedNotification)
        }
    }

    private fun buildBaseNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_wifi_adb)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(createPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildDetailedNotification(
        ip: String?,
        isAdbActive: Boolean,
        portInfo: AdbController.ActivePortInfo,
        configuredPort: Int
    ): Notification {
        val contentTitle = if (isAdbActive) {
            getString(R.string.notification_adb_active_title)
        } else {
            getString(R.string.notification_adb_inactive_title)
        }

        val dynamicTlsPort = AdbController.getDynamicTlsPort()
        val effectivePort = when {
            portInfo.port > 0 -> portInfo.port
            dynamicTlsPort != null -> dynamicTlsPort
            else -> configuredPort
        }
        val isSystemWireless = portInfo.isSystemWireless || (isAdbActive && effectivePort != configuredPort)
        val contentText = if (isAdbActive && !ip.isNullOrBlank()) {
            if (isSystemWireless && effectivePort > 0) {
                "${NetworkUtils.formatAdbConnectCommand(ip, effectivePort)} (${getString(R.string.status_system_wireless)})"
            } else if (isSystemWireless) {
                "adb connect $ip (${getString(R.string.status_system_wireless)})"
            } else {
                NetworkUtils.formatAdbConnectCommand(ip, effectivePort)
            }
        } else if (isAdbActive) {
            if (effectivePort > 0) {
                getString(R.string.notification_listening_no_ip, effectivePort)
            } else {
                getString(R.string.notification_running_base)
            }
        } else {
            getString(R.string.notification_stopped_hint)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_wifi_adb)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(createPendingIntent())
            .setOngoing(isAdbActive)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createPendingIntent(): PendingIntent {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, launchIntent, pendingIntentFlags)
    }
}
