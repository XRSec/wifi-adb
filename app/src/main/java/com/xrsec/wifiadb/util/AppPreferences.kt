package com.xrsec.wifiadb.util

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {

    private const val PREF_NAME = "wifi_adb_prefs"
    private const val KEY_LANGUAGE = "key_language"
    private const val KEY_PORT = "key_port"
    private const val KEY_BOOT_START = "key_boot_start"
    private const val KEY_AUTO_CONFIRM_ADB = "key_auto_confirm_adb"

    const val LANG_SYSTEM = "system"
    const val LANG_ZH = "zh"
    const val LANG_EN = "en"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getLanguage(context: Context): String {
        return getPrefs(context).getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
    }

    fun setLanguage(context: Context, lang: String) {
        getPrefs(context).edit().putString(KEY_LANGUAGE, lang).apply()
    }

    fun getPort(context: Context): Int {
        return getPrefs(context).getInt(KEY_PORT, 5555)
    }

    fun setPort(context: Context, port: Int) {
        getPrefs(context).edit().putInt(KEY_PORT, port).apply()
    }

    fun isBootStartEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BOOT_START, false)
    }

    fun setBootStartEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BOOT_START, enabled).apply()
    }

    fun isAutoConfirmAdbEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_CONFIRM_ADB, true)
    }

    fun setAutoConfirmAdbEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_CONFIRM_ADB, enabled).apply()
    }

    private const val KEY_ADB_KEY_PASSPHRASE = "key_adb_key_passphrase"

    // 启动自动化与固定端口配置键
    const val KEY_STARTUP_ENABLE_WIRELESS_DEBUG = "key_startup_enable_wireless_debug"
    const val KEY_STARTUP_ROOT_ENABLE_WIRELESS_DEBUG = "key_startup_root_enable_wireless_debug"
    const val KEY_STARTUP_DISABLE_AUTH_TIMEOUT = "key_startup_disable_auth_timeout"
    const val KEY_STARTUP_ROOT_DISABLE_AUTH_TIMEOUT = "key_startup_root_disable_auth_timeout"
    const val KEY_FIXED_PORT = KEY_PORT
    const val KEY_STARTUP_ROOT_FIX_PORT = "key_startup_root_fix_port"
    const val KEY_STARTUP_ADB_FIX_PORT = "key_startup_adb_fix_port"
    const val KEY_STARTUP_ADB_ACTIVATE_A11Y = "key_startup_adb_activate_a11y"
    const val KEY_STARTUP_ROOT_ACTIVATE_A11Y = "key_startup_root_activate_a11y"

    fun getAdbKeyPassphrase(context: Context): String {
        return getPrefs(context).getString(KEY_ADB_KEY_PASSPHRASE, "") ?: ""
    }

    fun setAdbKeyPassphrase(context: Context, passphrase: String) {
        getPrefs(context).edit().putString(KEY_ADB_KEY_PASSPHRASE, passphrase).apply()
    }

    fun isStartupEnableWirelessDebug(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STARTUP_ENABLE_WIRELESS_DEBUG, false)
    }

    fun setStartupEnableWirelessDebug(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STARTUP_ENABLE_WIRELESS_DEBUG, enabled).apply()
    }

    fun isStartupRootEnableWirelessDebug(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STARTUP_ROOT_ENABLE_WIRELESS_DEBUG, false)
    }

    fun setStartupRootEnableWirelessDebug(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STARTUP_ROOT_ENABLE_WIRELESS_DEBUG, enabled).apply()
    }

    fun isStartupDisableAuthTimeout(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STARTUP_DISABLE_AUTH_TIMEOUT, false)
    }

    fun setStartupDisableAuthTimeout(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STARTUP_DISABLE_AUTH_TIMEOUT, enabled).apply()
    }

    fun isStartupRootDisableAuthTimeout(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STARTUP_ROOT_DISABLE_AUTH_TIMEOUT, false)
    }

    fun setStartupRootDisableAuthTimeout(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STARTUP_ROOT_DISABLE_AUTH_TIMEOUT, enabled).apply()
    }

    fun getFixedPort(context: Context): Int {
        return getPort(context)
    }

    fun setFixedPort(context: Context, port: Int) {
        setPort(context, port)
    }

    fun isStartupRootFixPort(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STARTUP_ROOT_FIX_PORT, false)
    }

    fun setStartupRootFixPort(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STARTUP_ROOT_FIX_PORT, enabled).apply()
    }

    fun isStartupAdbFixPort(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STARTUP_ADB_FIX_PORT, false)
    }

    fun setStartupAdbFixPort(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STARTUP_ADB_FIX_PORT, enabled).apply()
    }

    fun isStartupAdbActivateA11y(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STARTUP_ADB_ACTIVATE_A11Y, false)
    }

    fun setStartupAdbActivateA11y(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STARTUP_ADB_ACTIVATE_A11Y, enabled).apply()
    }

    fun isStartupRootActivateA11y(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STARTUP_ROOT_ACTIVATE_A11Y, false)
    }

    fun setStartupRootActivateA11y(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STARTUP_ROOT_ACTIVATE_A11Y, enabled).apply()
    }
}
