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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 核心修复：收到 startForegroundService 请求后，第一行必须立即同步挂载基础前台通知，
        // 杜绝异步 Root 指令耗时超过 5 秒导致 ForegroundServiceDidNotStartInTimeException 崩溃。
        attachForeground(buildBaseNotification("WiFi ADB 服务运行中..."))

        val action = intent?.action ?: ACTION_UPDATE_NOTIFICATION

        serviceScope.launch {
            // 所有耗时操作（Root 命令执行、Socket 探测、网络解析）一律在 Dispatchers.IO 调度
            withContext(Dispatchers.IO) {
                when (action) {
                    ACTION_START_WIRELESS -> AdbController.startWirelessAdb()
                    ACTION_STOP_WIRELESS -> AdbController.stopWirelessAdb()
                    else -> {}
                }
            }
            // 异步获取最新状态后刷新前台通知内容
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
        serviceScope.launch {
            val (ip, isAdbActive) = withContext(Dispatchers.IO) {
                val currentIp = NetworkUtils.getLocalIpAddress(this@AdbWirelessService)
                val active = AdbController.isPortListening(AdbController.DEFAULT_PORT)
                Pair(currentIp, active)
            }

            val updatedNotification = buildDetailedNotification(ip, isAdbActive)
            notificationManager?.notify(NOTIFICATION_ID, updatedNotification)
        }
    }

    private fun buildBaseNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(createPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildDetailedNotification(ip: String?, isAdbActive: Boolean): Notification {
        val contentTitle = if (isAdbActive) {
            getString(R.string.notification_adb_active_title)
        } else {
            getString(R.string.notification_adb_inactive_title)
        }

        val contentText = if (isAdbActive && !ip.isNullOrBlank()) {
            NetworkUtils.formatAdbConnectCommand(ip)
        } else if (isAdbActive) {
            "端口 5555 正在监听，未获取到有效局域网 IP"
        } else {
            "无线调试已关闭，点击打开主界面"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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
