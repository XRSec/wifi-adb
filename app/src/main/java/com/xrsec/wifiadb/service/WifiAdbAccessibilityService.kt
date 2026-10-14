package com.xrsec.wifiadb.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xrsec.wifiadb.util.AppPreferences

class WifiAdbAccessibilityService : AccessibilityService() {

    companion object {
        var isRunning: Boolean = false
            private set

        @Volatile
        private var authPermitExpiryTime: Long = 0L

        /**
         * 授予限时授权许可：仅当本 App 主动发起 loopback ADB 握手连接时赋予时间窗口（默认 10 秒）。
         * 窗口过期或授权点击完成后立即销毁，杜绝任何外部未经允许的静默点击。
         */
        fun grantAuthPermit(durationMs: Long = 10000L) {
            authPermitExpiryTime = System.currentTimeMillis() + durationMs
        }

        fun isAuthPermitActive(): Boolean {
            return System.currentTimeMillis() < authPermitExpiryTime
        }

        fun consumeAuthPermit() {
            authPermitExpiryTime = 0L
        }

        /**
         * 静态检查系统无障碍列表中是否开启了当前辅助服务。
         */
        fun isServiceEnabled(context: Context): Boolean {
            val a11yEnabled = try {
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            } catch (_: Throwable) {
                false
            }
            if (!a11yEnabled) return false

            val expectedComponent = ComponentName(context, WifiAdbAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentStr = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentStr)
                if (enabledComponent != null &&
                    enabledComponent.packageName == expectedComponent.packageName &&
                    enabledComponent.className == expectedComponent.className
                ) {
                    return true
                }
            }
            return false
        }

        // 支持 AOSP 及国内主流定制系统 (MIUI/HyperOS, ColorOS, OriginOS, EMUI/HarmonyOS, OneUI) 的系统弹窗包名白名单
        private val ALLOWED_PACKAGES = setOf(
            "com.android.systemui",
            "com.google.android.systemui",
            "android",
            "com.android.settings",
            // MIUI / HyperOS
            "com.miui.securitycenter",
            "com.miui.securityadd",
            // ColorOS / Realme / OnePlus
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            // OriginOS / Vivo
            "com.iqoo.secure",
            "com.vivo.permissionmanager",
            // EMUI / HarmonyOS
            "com.huawei.systemmanager",
            // Samsung OneUI
            "com.samsung.android.app.cocktailbarservice"
        )

        // 深度 ADB 授权特征关键字（包含多语言与厂商定制文案）
        private val ADB_DIALOG_KEYWORDS = arrayOf(
            "RSA",
            "指纹",
            "fingerprint",
            "USB 调试",
            "USB debugging",
            "无线调试",
            "Wireless debugging",
            "允许调试",
            "调试授权",
            "计算机的 RSA",
            "Allow USB debugging",
            "Allow wireless debugging",
            "始终允许在此网络上进行调试",
            "Always allow on this network",
            "允许在此网络上"
        )

        // “一律允许”复选框特征文案
        private val ALWAYS_ALLOW_KEYWORDS = arrayOf(
            "始终允许在此网络上进行调试",
            "Always allow on this network",
            "在此网络上进行调试",
            "一律允许",
            "始终允许",
            "总是允许",
            "不再询问",
            "Always allow",
            "always allow from this computer"
        )

        // 确定/允许确认按钮特征文案
        private val ALLOW_BUTTON_KEYWORDS = arrayOf(
            "允许",
            "确定",
            "OK",
            "Allow",
            "ALLOW",
            "总是允许",
            "确定授权"
        )

        private const val MAX_TRAVERSE_DEPTH = 8
        private const val ACTION_COOLDOWN_MS = 600L
    }

    private var lastActionTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }

    override fun onInterrupt() {
        // 服务被中断
    }

    @Suppress("DEPRECATION")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 检查用户是否在设置中开启了“无障碍自动确认 ADB 授权”
        if (!AppPreferences.isAutoConfirmAdbEnabled(this)) {
            return
        }

        // 安全时效令牌校验：仅当本应用主动发起连接/握手并在有效窗口期内时，才自动确认授权，杜绝局域网外部未授权自动确认
        if (!isAuthPermitActive()) {
            return
        }

        // 防抖动与防死循环限制：避免密集内容变化事件导致反复触发
        val now = System.currentTimeMillis()
        if (now - lastActionTime < ACTION_COOLDOWN_MS) {
            return
        }

        // 1. 严格校验来源包名白名单，绝不扫描第三方普通 App 界面
        val pkg = event.packageName?.toString() ?: ""
        if (!ALLOWED_PACKAGES.contains(pkg)) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        try {
            // 2. 深度特征校验：只有包含 ADB 调试/RSA 特征关键字才认定为授权对话框
            if (!isAdbAuthDialog(rootNode, 0)) {
                return
            }

            // 3. 确实为 ADB 授权弹窗后，精准定位并勾选“一律允许/始终允许”复选框，随后点击允许
            val checkedAlways = findAndCheckAlwaysAllow(rootNode, 0)
            val clickedButton = findAndClickAllowButton(rootNode, 0)
            if (checkedAlways || clickedButton) {
                lastActionTime = System.currentTimeMillis()
            }
            if (clickedButton) {
                consumeAuthPermit()
            }
        } finally {
            rootNode.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun isAdbAuthDialog(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_TRAVERSE_DEPTH) return false
        val text = (node.text ?: node.contentDescription)?.toString() ?: ""
        if (text.isNotBlank()) {
            for (keyword in ADB_DIALOG_KEYWORDS) {
                if (text.contains(keyword, ignoreCase = true)) {
                    return true
                }
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val found = isAdbAuthDialog(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun findAndCheckAlwaysAllow(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_TRAVERSE_DEPTH) return false
        val text = (node.text ?: node.contentDescription)?.toString() ?: ""
        if (node.isCheckable) {
            var isTargetCheckbox = false
            for (keyword in ALWAYS_ALLOW_KEYWORDS) {
                if (text.contains(keyword, ignoreCase = true)) {
                    isTargetCheckbox = true
                    break
                }
            }
            // 若节点本身没有文字，检查其子节点或视图ID
            if (!isTargetCheckbox) {
                val resId = node.viewIdResourceName ?: ""
                if (resId.contains("always", ignoreCase = true)) {
                    isTargetCheckbox = true
                } else {
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        val childText = (child.text ?: child.contentDescription)?.toString() ?: ""
                        child.recycle()
                        for (keyword in ALWAYS_ALLOW_KEYWORDS) {
                            if (childText.contains(keyword, ignoreCase = true)) {
                                isTargetCheckbox = true
                                break
                            }
                        }
                        if (isTargetCheckbox) break
                    }
                }
            }

            if (isTargetCheckbox) {
                if (!node.isChecked) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                return true
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val handled = findAndCheckAlwaysAllow(child, depth + 1)
            child.recycle()
            if (handled) return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun findAndClickAllowButton(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_TRAVERSE_DEPTH) return false
        val text = (node.text ?: node.contentDescription)?.toString()?.trim() ?: ""
        if (node.isClickable && text.isNotBlank()) {
            for (keyword in ALLOW_BUTTON_KEYWORDS) {
                if (text.equals(keyword, ignoreCase = true)) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val handled = findAndClickAllowButton(child, depth + 1)
            child.recycle()
            if (handled) return true
        }
        return false
    }
}
