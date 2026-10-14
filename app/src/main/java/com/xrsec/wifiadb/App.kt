package com.xrsec.wifiadb

import android.app.Application
import android.content.Context
import com.xrsec.wifiadb.util.LocaleHelper

class App : Application() {

    companion object {
        lateinit var instance: App
            private set

        val context: Context
            get() = instance.applicationContext
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
