package com.example.performance

import android.app.Application
import android.util.Log
import com.example.nativelib.LooperMonitor
import com.example.nativelib.PerformanceSdk
import com.example.nativelib.thread.ThreadUtils

/**
 * @author mashanshui
 * @since 2025-12-21
 */
class App : Application() {
    private val TAG = "App"
    private var performanceSdk: PerformanceSdk? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            performanceSdk = PerformanceSdk.initialize(this, "")
        }.onFailure {
            Log.e(TAG, "Performance SDK initialization failed", it)
        }
        ThreadUtils.setMainThreadMaxPriority()
        LooperMonitor.sMainMonitor
    }
}
