package com.example.nativelib.fps

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.nativelib.ApplicationMetadata
import com.example.nativelib.FpsConfig
import com.example.nativelib.FpsLogLevel
import com.example.nativelib.config.NativeServiceConfig
import com.example.nativelib.crash.AndroidNetworkTypeProvider
import com.example.nativelib.network.FpsMetricEvent
import com.example.nativelib.network.FrameSceneSummaryPayload
import com.example.nativelib.network.NetworkClientFactory
import java.io.File
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * Activity 生命周期驱动的 FPS reporter。
 *
 * 页面帧回调只更新内存聚合；快照、封存、网络和重试都在 reporter 调度线程完成。
 */
internal class FpsReporter private constructor(
    private val application: Application,
    private val serviceConfig: NativeServiceConfig,
    private val fpsConfig: FpsConfig,
    private val metadata: ApplicationMetadata,
    private val buildId: String,
    private val anonymousDeviceId: String,
    private val networkFactory: NetworkClientFactory,
    private val scheduler: ScheduledExecutorService,
    private val store: FpsEventStore,
    private val uploader: FpsUploader,
    private val logLevel: FpsLogLevel,
) : Application.ActivityLifecycleCallbacks {
    private val closed = AtomicBoolean(false)
    private val acceptingSummaries = AtomicBoolean(true)
    private val flushInProgress = AtomicBoolean(false)
    private val stateLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fps-persistence").apply { isDaemon = true }
    }
    private val processAvailable = runCatching {
        isProcessAvailable(application)
    }.getOrDefault(false)
    private val helpers = WeakHashMap<Activity, FpsHelperV2>()
    private val sceneOverrides = WeakHashMap<Activity, String>()
    private val resumedActivities = WeakHashMap<Activity, Boolean>()
    private val pendingWindowStartRetry = WeakHashMap<Activity, Boolean>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var scheduledSnapshot: ScheduledFuture<*>? = null
    private var scheduledUpload: ScheduledFuture<*>? = null

    init {
        try {
            // 启动新会话前先恢复旧 current.json；事件 ID 在首次合并时生成并持续复用。
            store.startSession(UUID.randomUUID().toString())
            application.registerActivityLifecycleCallbacks(this)
            registerNetworkRecovery()
            scheduleTasks()
            reporterLog { "FPS reporter initialized available=$isAvailable" }
        } catch (throwable: Throwable) {
            persistenceExecutor.shutdownNow()
            throw throwable
        }
    }

    /** API 24、主进程、总开关和 FPS 子配置均满足时才允许注册页面帧监听。 */
    val isAvailable: Boolean
        get() = serviceConfig.fpsEnabled &&
            fpsConfig.enabled &&
            processAvailable

    /** 返回封存队列中的待上传 FPS 事件数量。 */
    fun pendingEventCount(): Int = store.count()

    /** 异步保存当前快照并刷新已经封存的事件；当前会话不会提前封存。 */
    fun flushAsync() {
        if (closed.get() || scheduler.isShutdown) {
            reporterLog { "flush ignored closed=${closed.get()} schedulerShutdown=${scheduler.isShutdown}" }
            return
        }
        scheduler.execute {
            runCatching { store.snapshot() }
                .onFailure { reporterLog { "flush snapshot failed type=${it.javaClass.simpleName}" } }
            flushInBackground()
        }
    }

    /** 设置指定 Activity 的稳定业务场景名；null 恢复 Activity 完整类名。 */
    fun setFpsScene(activity: Activity, scene: String?) {
        val targetScene = scene ?: activity.javaClass.name
        validateScene(targetScene)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setFpsScene(activity, targetScene) }
            return
        }
        synchronized(stateLock) {
            if (closed.get()) {
                reporterLog { "set scene ignored: reporter closed" }
                return
            }
            sceneOverrides[activity] = targetScene
            helpers[activity]?.setScene(targetScene)
            reporterLog { "scene override applied activity=${activity.javaClass.name} scene=$targetScene" }
        }
    }

    /** 关闭生命周期监听、停止页面采集、保存并封存当前会话。 */
    fun close() {
        if (!closed.compareAndSet(false, true)) {
            reporterLog { "close ignored: already closed" }
            return
        }
        application.unregisterActivityLifecycleCallbacks(this)
        unregisterNetworkCallback()
        synchronized(stateLock) {
            helpers.values.toList().forEach { helper -> helper.close() }
            helpers.clear()
            sceneOverrides.clear()
            resumedActivities.clear()
            pendingWindowStartRetry.clear()
        }
        acceptingSummaries.set(false)
        scheduledSnapshot?.cancel(false)
        scheduledUpload?.cancel(false)
        scheduler.shutdownNow()
        // close 的语义是封存当前会话，上传交给下一次初始化，避免和 SDK 网络资源关闭竞态。
        // 快照和封存放在专用后台线程执行，避免调用方线程直接承担磁盘 IO。
        val persistenceResult = runCatching {
            val task = persistenceExecutor.submit<Int> {
                store.snapshot()
                store.sealCurrent()
            }
            persistenceExecutor.shutdown()
            task.get()
        }.onFailure {
            persistenceExecutor.shutdownNow()
            reporterLog { "close persistence failed type=${it.javaClass.simpleName}" }
        }
        persistenceResult.getOrNull()?.let { sealedCount ->
            reporterLog { "FPS reporter closed and current session sealed count=$sealedCount" }
        }
    }

    /** Activity 进入创建阶段时不启动采集，等待 onActivityResumed 的可见状态。 */
    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
        reporterLog { "activity created name=${activity.javaClass.name}" }
    }

    /** Activity 进入启动阶段时不启动采集，避免统计不可见过渡动画。 */
    override fun onActivityStarted(activity: Activity) {
        reporterLog { "activity started name=${activity.javaClass.name}" }
    }

    /** Activity 恢复后注册 FrameMetrics 监听，只统计真实可见页面刷新。 */
    override fun onActivityResumed(activity: Activity) {
        synchronized(stateLock) {
            resumedActivities[activity] = true
        }
        if (!isAvailable || closed.get()) {
            reporterLog { "activity resume skipped name=${activity.javaClass.name} available=$isAvailable" }
            return
        }
        // ActivityLifecycleCallbacks 的 resumed 回调早于 Window 加入 ViewRoot，延后一轮消息
        // 才能可靠读取硬件加速状态；同时由 resumedActivities 阻断暂停期间的迟到任务。
        mainHandler.post { startSamplingAfterWindowAttach(activity) }
    }

    /** Activity 暂停时结算页面当前刷新率桶，静止或后台时间不会进入分母。 */
    override fun onActivityPaused(activity: Activity) {
        synchronized(stateLock) {
            resumedActivities[activity] = false
            pendingWindowStartRetry.remove(activity)
            helpers[activity]?.stop()
            reporterLog { "activity paused sampling stopped name=${activity.javaClass.name}" }
        }
    }

    /** Activity 停止阶段仅输出生命周期诊断，不重复关闭采集器。 */
    override fun onActivityStopped(activity: Activity) {
        reporterLog { "activity stopped name=${activity.javaClass.name}" }
    }

    /** Activity 销毁时兜底关闭 Helper，防止 Window 和 HandlerThread 被长期持有。 */
    override fun onActivityDestroyed(activity: Activity) {
        synchronized(stateLock) {
            helpers.remove(activity)?.close()
            sceneOverrides.remove(activity)
            resumedActivities.remove(activity)
            pendingWindowStartRetry.remove(activity)
            reporterLog { "activity destroyed helper released name=${activity.javaClass.name}" }
        }
    }

    /**
     * 在 Window 完成挂载后启动页面采集；执行于主线程，失败只记录稳定原因。
     * 首次检查若仍未挂载，最多延后一帧重试，避免无限定时任务或暂停后的迟到注册。
     */
    private fun startSamplingAfterWindowAttach(activity: Activity) {
        synchronized(stateLock) {
            if (closed.get() || resumedActivities[activity] != true) {
                reporterLog { "activity resume sampling ignored stale lifecycle name=${activity.javaClass.name}" }
                return
            }
            if (!activity.window.decorView.isAttachedToWindow) {
                if (pendingWindowStartRetry.put(activity, true) != true) {
                    mainHandler.postDelayed(
                        {
                            synchronized(stateLock) {
                                pendingWindowStartRetry.remove(activity)
                            }
                            startSamplingAfterWindowAttach(activity)
                        },
                        WINDOW_START_RETRY_DELAY_MILLIS,
                    )
                    reporterLog {
                        "activity resume delayed until Window attached name=${activity.javaClass.name}"
                    }
                } else {
                    reporterLog {
                        "activity resume skipped unattached Window name=${activity.javaClass.name}"
                    }
                }
                return
            }
            if (!activity.window.decorView.isHardwareAccelerated) {
                reporterLog { "activity resume skipped nonHardware name=${activity.javaClass.name}" }
                return
            }
            val scene = sceneOverrides[activity] ?: activity.javaClass.name
            val helper = helpers.getOrPut(activity) {
                FpsHelperV2(
                    logLevel = logLevel,
                    summaryListener = { summary -> onSummary(summary) },
                )
            }
            runCatching { helper.start(activity.window, scene) }
                .onSuccess {
                    reporterLog {
                        "activity resume sampling started name=${activity.javaClass.name} scene=$scene"
                    }
                }
                .onFailure { throwable ->
                    reporterLog {
                        "activity resume sampling failed name=${activity.javaClass.name} " +
                            "type=${throwable.javaClass.simpleName}"
                    }
                }
        }
    }

    /** Activity 保存状态回调不改变 FPS 聚合，生命周期日志只在开启时输出。 */
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {
        reporterLog { "activity state saved name=${activity.javaClass.name}" }
    }

    /** 接收 Helper 的内存快照并合并到当前会话，不执行磁盘和网络操作。 */
    private fun onSummary(summary: FpsWindowSummary) {
        if (!acceptingSummaries.get() || summary.uiRefreshFrameCount <= 0L) {
            reporterLog { "summary ignored closed=${closed.get()} frameCount=${summary.uiRefreshFrameCount}" }
            return
        }
        store.merge(summary)
        reporterLog {
            "summary merged scene=${summary.scene} refreshRateHz=${summary.refreshRateHz} " +
                "frames=${summary.uiRefreshFrameCount} activeDurationMs=${summary.activeDurationMs}"
        }
    }

    /** 建立快照和上传定时任务；任务只操作后台队列，不触碰 Activity。 */
    private fun scheduleTasks() {
        scheduledSnapshot = scheduler.scheduleAtFixedRate(
            {
                runCatching { store.snapshot() }
                    .onFailure { reporterLog { "snapshot failed type=${it.javaClass.simpleName}" } }
            },
            fpsConfig.snapshotIntervalMillis,
            fpsConfig.snapshotIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
        scheduledUpload = scheduler.scheduleAtFixedRate(
            { flushInBackground() },
            0L,
            fpsConfig.uploadIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    /** 在单线程调度器中执行一次队列刷新，避免多个上传循环并发修改重试状态。 */
    private fun flushInBackground() {
        if (!flushInProgress.compareAndSet(false, true) || closed.get()) {
            return
        }
        try {
            val report = runBlocking { uploader.flush() }
            if (report.batchesSent > 0 || report.eventsDeadLettered > 0) {
                reporterLog {
                    "upload completed batches=${report.batchesSent} " +
                        "acknowledged=${report.eventsAcknowledged} retried=${report.eventsRetried} " +
                        "deadLettered=${report.eventsDeadLettered}"
                }
            }
        } catch (throwable: Throwable) {
            reporterLog { "upload cycle failed type=${throwable.javaClass.simpleName}" }
        } finally {
            flushInProgress.set(false)
        }
    }

    /** API 24 以上注册默认网络恢复回调，网络恢复后立即触发一次上传。 */
    private fun registerNetworkRecovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }
        val manager = application.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            /** 网络可用时安排一次后台刷新。 */
            override fun onAvailable(network: Network) {
                flushAsync()
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure {
            reporterLog { "network recovery callback registration failed type=${it.javaClass.simpleName}" }
        }
    }

    /** 移除网络恢复回调，关闭 reporter 后不再持有系统服务。 */
    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val manager = application.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    /** 校验业务场景名，防止动态内容进入维度、文件和日志。 */
    private fun validateScene(scene: String) {
        require(scene.isNotBlank()) { "FPS scene must not be blank" }
        require(scene.length <= 128) { "FPS scene must be at most 128 characters" }
        require(scene.none { it.isISOControl() }) {
            "FPS scene must not contain control characters"
        }
    }

    /** 只在开启 SUMMARY 时计算并输出 reporter 日志，OFF 模式不构造字符串。 */
    private inline fun reporterLog(message: () -> String) {
        if (logLevel.ordinal >= FpsLogLevel.SUMMARY.ordinal) {
            Log.i(TAG, message())
        }
    }

    internal companion object {
        const val TAG = "FpsReporter"
        const val QUEUE_DIRECTORY = "performance-fps-reporter"
        const val FPS_SCHEMA_VERSION = 2
        private const val WINDOW_START_RETRY_DELAY_MILLIS = 16L

        /** 判断当前进程是否具备 FPS 采集条件，避免低版本或子进程创建 reporter。 */
        fun isProcessAvailable(application: Application): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return false
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                // API 28 以下通过系统提供的当前进程信息判断，避免把子进程误认为主进程。
                val processInfo = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(processInfo)
                return application.packageName == processInfo.processName
            }
            return application.packageName == Application.getProcessName()
        }

        /** 创建并注册生产 FPS reporter。 */
        fun create(
            application: Application,
            serviceConfig: NativeServiceConfig,
            fpsConfig: FpsConfig,
            metadata: ApplicationMetadata,
            buildId: String,
            anonymousDeviceId: String,
            networkFactory: NetworkClientFactory,
        ): FpsReporter {
            val context = application.applicationContext
            val root = File(context.noBackupFilesDir, QUEUE_DIRECTORY)
            lateinit var store: FpsEventStore
            val eventBuilder: (FpsAggregateRecord) -> FpsMetricEvent = { aggregate ->
                val activeDurationMs = ceilMillis(aggregate.activeDurationNs)
                val rawFps = if (aggregate.activeDurationNs > 0L) {
                    aggregate.uiRefreshFrameCount * 1_000_000_000.0 / aggregate.activeDurationNs
                } else {
                    0.0
                }
                val normalizedFps60 = minOf(
                    60.0,
                    rawFps * 60.0 / aggregate.refreshRateHz.coerceAtLeast(1.0),
                )
                FpsMetricEvent(
                    schemaVersion = FPS_SCHEMA_VERSION,
                    eventId = aggregate.eventId,
                    eventType = "frame_scene_summary",
                    occurredAt = aggregate.occurredAtMillis,
                    sessionId = aggregate.sessionId,
                    anonymousDeviceId = anonymousDeviceId,
                    packageName = metadata.packageName,
                    appVersion = metadata.versionName,
                    versionCode = metadata.versionCode,
                    buildId = buildId,
                    environment = serviceConfig.environment,
                    channel = serviceConfig.channel,
                    osVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" },
                    deviceModel = Build.MODEL.orEmpty().ifBlank { "unknown" },
                    networkType = AndroidNetworkTypeProvider(context).invoke(),
                    frameSceneSummary = FrameSceneSummaryPayload(
                        scene = aggregate.scene,
                        algorithmVersion = aggregate.algorithmVersion,
                        activeDurationMs = activeDurationMs,
                        uiRefreshFrameCount = aggregate.uiRefreshFrameCount,
                        refreshRateHz = aggregate.refreshRateHz,
                        normalizedFps60 = normalizedFps60,
                    ),
                )
            }
            store = FpsEventStore(
                root = root,
                eventBuilder = eventBuilder,
                diskQuotaBytes = serviceConfig.fpsQueueDiskQuotaBytes,
                eventTtlMillis = serviceConfig.fpsEventTtlMillis,
                logger = { message ->
                    if (fpsConfig.logLevel.ordinal >= FpsLogLevel.SUMMARY.ordinal) {
                        Log.i(TAG, message())
                    }
                },
            )
            val uploader = FpsUploader(
                store = store,
                sender = networkFactory.fpsNetworkClient.asFpsBatchSender(),
                config = fpsConfig,
                logger = { message ->
                    if (fpsConfig.logLevel.ordinal >= FpsLogLevel.SUMMARY.ordinal) {
                        Log.i(TAG, message())
                    }
                },
            )
            val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "fps-upload").apply { isDaemon = true }
            }
            return try {
                FpsReporter(
                    application = application,
                    serviceConfig = serviceConfig,
                    fpsConfig = fpsConfig,
                    metadata = metadata,
                    buildId = buildId,
                    anonymousDeviceId = anonymousDeviceId,
                    networkFactory = networkFactory,
                    scheduler = scheduler,
                    store = store,
                    uploader = uploader,
                    logLevel = fpsConfig.logLevel,
                )
            } catch (throwable: Throwable) {
                scheduler.shutdownNow()
                throw throwable
            }
        }

        /** 将饱和纳秒时长向上转换成服务端需要的毫秒值。 */
        private fun ceilMillis(nanos: Long): Long {
            if (nanos <= 0L) {
                return 0L
            }
            return if (nanos > Long.MAX_VALUE - 999_999L) {
                Long.MAX_VALUE / 1_000_000L
            } else {
                (nanos + 999_999L) / 1_000_000L
            }
        }
    }
}
