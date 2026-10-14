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

        /**
         * 静态检查系统无障碍列表中是否开启了当前辅助服务。
         */
        fun isServiceEnabled(context: Context): Boolean {
            val expectedComponent = ComponentName(context, WifiAdbAccessibilityService::class.java).flattenToString()
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedComponent, ignoreCase = true)) {
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
            "Allow wireless debugging"
        )

        // “一律允许”复选框特征文案
        private val ALWAYS_ALLOW_KEYWORDS = arrayOf(
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
    }

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

            // 3. 确实为 ADB 授权弹窗后，精准定位并勾选“一律允许”复选框，随后点击允许
            findAndCheckAlwaysAllow(rootNode, 0)
            findAndClickAllowButton(rootNode, 0)
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
        if (node.isCheckable) {
            val text = (node.text ?: node.contentDescription)?.toString() ?: ""
            for (keyword in ALWAYS_ALLOW_KEYWORDS) {
                if (text.contains(keyword, ignoreCase = true)) {
                    if (!node.isChecked) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    return true
                }
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
