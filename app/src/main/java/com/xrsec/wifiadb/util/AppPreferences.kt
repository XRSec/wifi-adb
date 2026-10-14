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
}
