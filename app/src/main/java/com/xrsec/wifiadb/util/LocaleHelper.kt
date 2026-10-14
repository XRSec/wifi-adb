package com.xrsec.wifiadb.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {

    fun wrap(context: Context): Context {
        val lang = AppPreferences.getLanguage(context)
        return applyLocale(context, lang)
    }

    fun applyLocale(context: Context, language: String): Context {
        val targetLocale = when (language) {
            AppPreferences.LANG_ZH -> Locale.SIMPLIFIED_CHINESE
            AppPreferences.LANG_EN -> Locale.ENGLISH
            else -> getSystemLocale()
        }

        Locale.setDefault(targetLocale)
        val res = context.resources
        val config = Configuration(res.configuration)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(targetLocale))
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = targetLocale
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            context
        }
    }

    private fun getSystemLocale(): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Resources.getSystem().configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            Resources.getSystem().configuration.locale
        }
    }
}
