package com.example.performance

import android.app.Application
import android.util.Log
import com.example.nativelib.LooperMonitor
import com.example.nativelib.FpsConfig
import com.example.nativelib.FpsLogLevel
import com.example.nativelib.JankConfig
import com.example.nativelib.PerformanceConfig
import com.example.nativelib.PerformanceSdk
import com.example.nativelib.thread.ThreadUtils

/**
 * @author mashanshui
 * @since 2025-12-21
 */
class App : Application() {
    private val TAG = "App"
    private var performanceSdk: PerformanceSdk? = null

    /** 初始化 SDK，并在示例应用中打开页面 FPS 摘要日志。 */
    override fun onCreate() {
        super.onCreate()
        runCatching {
            performanceSdk = PerformanceSdk.initialize(
                application = this,
                appKey = "apm_ak_wZE86RWHGz6Eg6WpI8MTS8d_l8rROU3JiD4ITFr3UC8",
                config = PerformanceConfig(
                    jank = JankConfig(
                        fps = FpsConfig(logLevel = FpsLogLevel.SUMMARY),
                        enableStackCaptureStats = true,
                        enableObjectAllocation = true,
                        enableWakeup = true,
                        enableRusage = true,
                        enableJniHook = true
                    ),
                ),
            )
        }.onFailure {
            Log.e(TAG, "Performance SDK initialization failed", it)
        }
        ThreadUtils.setMainThreadMaxPriority()
        LooperMonitor.sMainMonitor
    }
}
