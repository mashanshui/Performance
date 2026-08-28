package com.example.performance

import android.app.Application
import android.util.Log
import com.example.nativelib.LooperMonitor
import com.example.nativelib.crash.CrashReporter
import com.example.nativelib.crash.CrashReporterConfig
import com.example.nativelib.hook.HookUtils
import com.example.nativelib.thread.ThreadUtils

/**
 * @author mashanshui
 * @since 2025-12-21
 */
class App : Application() {
    private val TAG = "App"
    private var crashReporter: CrashReporter? = null

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.CRASH_ENABLED) {
            runCatching {
                crashReporter = CrashReporter.initialize(
                    context = this,
                    config = CrashReporterConfig(
                        baseUrl = BuildConfig.CRASH_BASE_URL,
                        projectKey = BuildConfig.CRASH_PROJECT_KEY,
                        appId = BuildConfig.CRASH_APP_ID.ifBlank { BuildConfig.APPLICATION_ID },
                        appVersion = BuildConfig.VERSION_NAME.orEmpty().ifBlank { "unknown" },
                        versionCode = BuildConfig.VERSION_CODE,
                        buildId = BuildConfig.CRASH_BUILD_ID.ifBlank {
                            "${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}"
                        },
                        environment = BuildConfig.CRASH_ENVIRONMENT,
                        channel = BuildConfig.CRASH_CHANNEL,
                        applicationPackage = BuildConfig.APPLICATION_ID,
                        enableNetworkLogging = BuildConfig.CRASH_LOGGING,
                    ),
                )
            }.onFailure {
                Log.e(TAG, "Crash reporter initialization failed", it)
            }
        }
        HookUtils.initHook()
        ThreadUtils.bindMainThreadToMaxCore()
        ThreadUtils.setMainThreadMaxPriority()
        LooperMonitor.sMainMonitor
    }
}
