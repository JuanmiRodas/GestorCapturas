package com.example.screenshotmanager

import android.app.Application

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.e("Crash in ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }
}
