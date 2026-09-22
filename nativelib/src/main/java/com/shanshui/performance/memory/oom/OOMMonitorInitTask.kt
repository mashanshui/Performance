package com.shanshui.performance.memory.oom

import android.app.Application
import android.app.ActivityManager
import android.os.Build
import com.shanshui.performance.memory.leak.MemoryLeakReportReporter
import com.kwai.koom.base.DefaultInitTask
import com.kwai.koom.base.MonitorLog
import com.kwai.koom.base.MonitorManager
import com.kwai.koom.javaoom.monitor.OOMHprofUploader
import com.kwai.koom.javaoom.monitor.OOMMonitor
import com.kwai.koom.javaoom.monitor.OOMMonitorConfig
import com.kwai.koom.javaoom.monitor.OOMReportUploader
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object OOMMonitorInitTask {
    private const val TAG = "OOMMonitorInitTask"
    private const val KOOM_HEAP_ANALYSIS_PROCESS_SUFFIX = ":heap_analysis"
    private const val LOOP_START_DELAY_MILLIS = 5_000L
    private val stateLock = Any()
    private val baseInitialized = AtomicBoolean(false)
    private val configRegistered = AtomicBoolean(false)
    private val loopStarted = AtomicBoolean(false)
    private val reportReporter = AtomicReference<MemoryLeakReportReporter?>()
    private val monitorConfig by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OOMMonitorConfig.Builder()
            .setThreadThreshold(50) //50 only for test! Please use default value!
            .setFdThreshold(300) // 300 only for test! Please use default value!
            .setHeapThreshold(0.9f) // 0.9f for test! Please use default value!
            .setVssSizeThreshold(1_000_000) // 1_000_000 for test! Please use default value!
            .setMaxOverThresholdCount(1) // 1 for test! Please use default value!
            .setAnalysisMaxTimesPerVersion(3) // Consider use default value！
            .setAnalysisPeriodPerVersion(15 * 24 * 60 * 60 * 1000) // Consider use default value！
            .setLoopInterval(5_000) // 5_000 for test! Please use default value!
            .setEnableHprofDumpAnalysis(true)
            .setHprofUploader(object : OOMHprofUploader {
                override fun upload(file: File, type: OOMHprofUploader.HprofType) {
                    // 本阶段不上传 HPROF；按 KOOM 回调约定清理本地大文件。
                    if (file.exists() && !file.delete()) {
                        MonitorLog.e("OOMMonitor", "unable to delete ignored hprof ${file.name}")
                    }
                }
            })
            .setReportUploader(object : OOMReportUploader {
                override fun upload(file: File, content: String) {
                    val reporter = reportReporter.get()
                    if (reporter == null) {
                        MonitorLog.e("OOMMonitor", "memory leak report uploader is unavailable")
                        return
                    }
                    if (!reporter.enqueueReport(file, content)) {
                        MonitorLog.e("OOMMonitor", "unable to enqueue report ${file.name}")
                    }
                }
            })
            .build()
    }

    /** 与 KOOM DefaultInitTask 的 sdkVersionMatch 保持一致，只接受 API 21..36。 */
    internal fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT in Build.VERSION_CODES.LOLLIPOP..36
    }

    /** 判断 KOOM HeapAnalysisService 使用的独立进程。 */
    internal fun isHeapAnalysisProcess(application: Application): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val processInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(processInfo)
            processInfo.processName
        }
        return processName == application.packageName + KOOM_HEAP_ANALYSIS_PROCESS_SUFFIX
    }

    /**
     * 确保当前进程具备 KOOM 的公共配置。
     *
     * KOOM 的 HeapAnalysisService 运行在独立的 :heap_analysis 进程，
     * 该进程不会进入主进程的 OOMMonitor 配置注册流程，但分析文件初始化
     * 仍会读取 MonitorManager.commonConfig，因此必须先执行 DefaultInitTask。
     * 这里只初始化 CommonConfig，不注册监控配置，也不启动 OOM 循环。
     */
    internal fun ensureCommonConfig(application: Application) {
        synchronized(stateLock) {
            if (!baseInitialized.compareAndSet(false, true)) {
                return
            }
            try {
                DefaultInitTask.init(application)
            } catch (throwable: Throwable) {
                baseInitialized.set(false)
                throw throwable
            }
        }
    }

    internal fun init(application: Application, reporter: MemoryLeakReportReporter) {
        reportReporter.set(reporter)
        try {
            synchronized(stateLock) {
                // DefaultInitTask 会初始化 KOOM CommonConfig 和 loop Handler；同一进程只执行一次。
                ensureCommonConfigLocked(application)
                // MonitorManager 自身也会去重，这里的原子状态保证不会重复构造或注册配置。
                if (configRegistered.compareAndSet(false, true)) {
                    try {
                        MonitorManager.addMonitorConfig(monitorConfig)
                    } catch (throwable: Throwable) {
                        configRegistered.set(false)
                        throw throwable
                    }
                }
            }

            // stopLoop 只停止本次循环，不重置 KOOM 的进程级 mHasDumped。
            OOMMonitor.startLoop(true, false, LOOP_START_DELAY_MILLIS)
            loopStarted.set(true)
        } catch (throwable: Throwable) {
            reportReporter.compareAndSet(reporter, null)
            reporter.close()
            MonitorLog.e(TAG, "unable to start OOM monitor loop type=${throwable.javaClass.simpleName}")
            throw throwable
        }
    }

    /** 调用方已经持有 [stateLock] 时使用，避免重复加锁。 */
    private fun ensureCommonConfigLocked(application: Application) {
        if (!baseInitialized.compareAndSet(false, true)) {
            return
        }
        try {
            DefaultInitTask.init(application)
        } catch (throwable: Throwable) {
            baseInitialized.set(false)
            throw throwable
        }
    }

    /** 停止 KOOM 后台循环并关闭报告上传器；不会触碰 KOOM 的进程级 dump 状态。 */
    fun stop() {
        if (loopStarted.compareAndSet(true, false)) {
            runCatching { OOMMonitor.stopLoop() }
                .onFailure {
                    MonitorLog.e(TAG, "unable to stop OOM monitor loop type=${it.javaClass.simpleName}")
                }
        }
        reportReporter.getAndSet(null)?.let { reporter ->
            runCatching { reporter.close() }
                .onFailure {
                    MonitorLog.e(TAG, "unable to close memory leak report uploader")
                }
        }
    }

    fun dump() {
        OOMMonitor.dumpAndAnalysis()
    }
}
