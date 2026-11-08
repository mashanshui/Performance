package com.shanshui.performance

import android.app.Application
import android.util.Log
import com.shanshui.performance.thread.ThreadUtils

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
            /** 仅显式 Debug 测试构建启用，正常示例保持原组件配置。 */
            val joint = BuildConfig.DEBUG && BuildConfig.PERFORMANCE_MEMORY_JOINT_TEST
            if (joint) {
                // 故障类只存在于 debug 源集；生产构建不会暴露故障入口。
                Class.forName("com.shanshui.performance.MemoryJointStartupFault")
                    .getMethod("run", Application::class.java).invoke(null, this)
            }
            /** 默认服务配置，只有显式传入地址才覆盖。 */
            val service = ServiceConfig(enableNetworkLogging = true, buildId = BuildConfig.PERFORMANCE_BUILD_ID)
            performanceSdk = PerformanceSdk.initialize(
                application = this,
                appKey = BuildConfig.PERFORMANCE_APP_KEY,
                config = PerformanceConfig(
                    service = if (BuildConfig.PERFORMANCE_BASE_URL.isBlank()) service else service.copy(baseUrl = BuildConfig.PERFORMANCE_BASE_URL),
                    crash = CrashConfig(enabled = !joint),
                    // 使用 app 模块在本次 Gradle 命令中生成的 buildId。
                    jank = if (joint) JankConfig(enabled = false) else JANK_TEST_CONFIG,
                    fps = FpsConfig(enabled = !joint, logLevel = FpsLogLevel.SUMMARY),
                    memoryLeak = MemoryLeakConfig(
                        enabled = !joint,
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
    companion object {
        /** 示例固定使用纯异步；对比同步、混合或全关时仅修改这里的参数。 */
        val JANK_TEST_CONFIG = JankConfig(
            enableSyncSampling = false,
            enableAsyncSampling = true,
            asyncSampleIntervalMillis = 50L,
            enableStackCaptureStats = true,
            enableObjectAllocation = true,
            enableWakeup = true,
            enableRusage = true,
            enableJniHook = true,
        )
    }

}
