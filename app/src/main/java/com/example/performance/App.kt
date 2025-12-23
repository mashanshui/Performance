package com.example.performance

import android.app.Application
import com.example.nativelib.hook.HookUtils
import com.example.nativelib.thread.ThreadUtils

/**
 * @author mashanshui
 * @since 2025-12-21
 */
class App : Application() {
    private val TAG = "App"
    override fun onCreate() {
        super.onCreate()
        HookUtils.initHook()
        ThreadUtils.bindMainThreadToMaxCore()
        ThreadUtils.setMainThreadMaxPriority()
        ThreadUtils.setRenderThreadMaxPriority()
    }
}