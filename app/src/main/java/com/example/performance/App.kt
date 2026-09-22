package com.example.performance

import android.app.Application
import android.util.Log
import com.example.nativelib.CrashConfig
import com.example.nativelib.FpsConfig
import com.example.nativelib.FpsLogLevel
import com.example.nativelib.JankConfig
import com.example.nativelib.LooperMonitor
import com.example.nativelib.MemoryLeakConfig
import com.example.nativelib.PerformanceConfig
import com.example.nativelib.PerformanceSdk
import com.example.nativelib.ServiceConfig
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
                appKey = "apm_ak_UowWFkzFaZcYmP64C761KRR41ySgjuZ6FL9-P5bJ6x8",
                config = PerformanceConfig(
                    service = ServiceConfig(enableNetworkLogging = true),
                    // 使用 app 模块在本次 Gradle 命令中生成的 buildId。
                    crash = CrashConfig(
                        buildId = BuildConfig.PERFORMANCE_BUILD_ID,
                    ),
                    jank = JankConfig(
                        fps = FpsConfig(logLevel = FpsLogLevel.SUMMARY),
                        enableStackCaptureStats = true,
                        enableObjectAllocation = true,
                        enableWakeup = true,
                        enableRusage = true,
                        enableJniHook = true
                    ),
                    memoryLeak = MemoryLeakConfig(
                        enabled = true,
                        foregroundScanIntervalMillis = 5_000L,
                        backgroundScanIntervalMillis = 5_000L,
                        maxRecheckCount = 1,
                        gcDelayMillis = 1_000L,
                        // Android Studio 连接调试器时也执行检测
                        skipWhenDebuggerConnected = false,
                    ),
                ),
            )
        }.onSuccess {
            Log.i(
                TAG,
                "Performance SDK initialized: memoryLeakAvailable=" +
                    "${performanceSdk?.isMemoryLeakAvailable == true}",
            )
        }.onFailure {
            Log.e(TAG, "Performance SDK initialization failed", it)
        }
        ThreadUtils.setMainThreadMaxPriority()
        LooperMonitor.sMainMonitor
    }
}
