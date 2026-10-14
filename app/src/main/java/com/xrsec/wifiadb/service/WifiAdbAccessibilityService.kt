package com.xrsec.wifiadb.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

        // 仅处理系统核心弹窗包名，严禁拦截第三方应用或敏感界面
        private val ALLOWED_PACKAGES = setOf(
            "com.android.systemui",
            "com.google.android.systemui",
            "android"
        )

        // 必须包含 ADB 调试特征关键字才判定为授权对话框
        private val ADB_DIALOG_KEYWORDS = arrayOf(
            "RSA",
            "指纹",
            "fingerprint",
            "USB 调试",
            "USB debugging",
            "无线调试",
            "Wireless debugging",
            "允许调试"
        )

        // ADB 调试授权“一律允许”复选框文案
        private val ALWAYS_ALLOW_KEYWORDS = arrayOf(
            "一律允许",
            "始终允许",
            "总是允许",
            "Always allow",
            "always allow from this computer"
        )

        // ADB 调试授权确认按钮文案
        private val ALLOW_BUTTON_KEYWORDS = arrayOf(
            "允许",
            "确定",
            "OK",
            "Allow",
            "ALLOW"
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

        // 1. 严格校验来源包名，仅限系统 SystemUI 或 Android 框架弹窗
        val pkg = event.packageName?.toString() ?: ""
        if (!ALLOWED_PACKAGES.contains(pkg)) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        try {
            // 2. 校验当前弹窗是否包含 ADB 调试特征关键字，防止误触普通系统权限或敏感弹窗
            if (!isAdbAuthDialog(rootNode, 0)) {
                return
            }

            // 3. 确实为 ADB 授权弹窗后，再执行复选框勾选与允许按钮点击
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
