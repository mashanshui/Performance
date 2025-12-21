package com.example.performance

import android.app.Application
import android.util.Log
import com.example.nativelib.cpuusage.BindCoreUtils

/**
 * @author mashanshui
 * @since 2025-12-21
 */
class App : Application() {
    private val TAG = "App"
    override fun onCreate() {
        super.onCreate()
        val result = BindCoreUtils.bindMainThreadToMaxCore()
        Log.e(TAG, "onCreate: " + result)
    }
}