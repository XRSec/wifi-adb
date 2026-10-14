package com.xrsec.wifiadb.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object PowerOptimizationHelper {

    private val AUTO_START_INTENTS = listOf(
        // Xiaomi / Redmi / POCO
        listOf("xiaomi", "redmi", "blackshark") to listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter"
        ),
        // Huawei / Honor
        listOf("huawei", "honor") to listOf(
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager/.optimize.process.ProtectActivity",
            "com.huawei.systemmanager/.optimize.bootstart.BootStartActivity",
            "com.hihonor.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager",
            "com.huawei.systemmanager"
        ),
        // vivo / iQOO
        listOf("vivo", "iqoo") to listOf(
            "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure/.safeguard.PurviewTabActivity",
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
            "com.iqoo.secure",
            "com.vivo.permissionmanager"
        ),
        // OPPO / Realme / OnePlus
        listOf("oppo", "realme", "oneplus") to listOf(
            "com.coloros.safecenter/.startupapp.StartupAppListActivity",
            "com.coloros.safecenter/.permission.startup.StartupAppListActivity",
            "com.oppo.safe/.permission.startup.StartupAppListActivity",
            "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity",
            "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
            "com.coloros.safecenter/com.coloros.privacypermissionsentry.PermissionTopActivity",
            "com.coloros.safecenter",
            "com.oppo.safe",
            "com.oneplus.security",
            "com.coloros.oppoguardelf"
        ),
        // Samsung
        listOf("samsung") to listOf(
            "com.samsung.android.sm_cn/com.samsung.android.sm.ui.ram.AutoRunActivity",
            "com.samsung.android.sm_cn/com.samsung.android.sm.ui.appmanagement.AppManagementActivity",
            "com.samsung.android.sm_cn/com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity",
            "com.samsung.android.sm_cn/.ui.ram.RamActivity",
            "com.samsung.android.sm_cn/.app.dashboard.SmartManagerDashBoardActivity",
            "com.samsung.android.sm/com.samsung.android.sm.ui.ram.AutoRunActivity",
            "com.samsung.android.sm/com.samsung.android.sm.ui.appmanagement.AppManagementActivity",
            "com.samsung.android.sm/com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity",
            "com.samsung.android.sm/.ui.ram.RamActivity",
            "com.samsung.android.sm/.app.dashboard.SmartManagerDashBoardActivity",
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.sm_cn",
            "com.samsung.android.sm"
        ),
        // Meizu
        listOf("meizu") to listOf(
            "com.meizu.safe/.permission.SmartBGActivity",
            "com.meizu.safe/.permission.PermissionMainActivity",
            "com.meizu.safe"
        )
    )

    /**
     * 检查当前应用是否已被加入电池优化白名单 (忽略电池优化)
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        }.getOrDefault(false)
    }

    /**
     * 引导用户请求加入电池优化白名单
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                if (context !is android.app.Activity) {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
            true
        }.recoverCatching {
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                if (context !is android.app.Activity) {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(fallbackIntent)
            true
        }.getOrDefault(false)
    }

    /**
     * 打开各品牌厂商专属的系统自启动管理页面
     */
    fun openAutoStartManagement(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val targetList = AUTO_START_INTENTS.firstOrNull { (brands, _) ->
            brands.any { manufacturer.contains(it) }
        }?.second.orEmpty()

        for (target in targetList) {
            val launched = runCatching {
                val intent = if (target.contains("/")) {
                    val comp = ComponentName.unflattenFromString(target) ?: return@runCatching false
                    Intent().apply {
                        component = comp
                        if (context !is android.app.Activity) {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    }
                } else {
                    context.packageManager.getLaunchIntentForPackage(target)?.apply {
                        if (context !is android.app.Activity) {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    }
                }
                if (intent != null) {
                    context.startActivity(intent)
                    true
                } else false
            }.getOrDefault(false)

            if (launched) return true
        }

        // 兜底方案 1: 应用详情设置页面
        return runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                if (context !is android.app.Activity) {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
            true
        }.recoverCatching {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                if (context !is android.app.Activity) {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
