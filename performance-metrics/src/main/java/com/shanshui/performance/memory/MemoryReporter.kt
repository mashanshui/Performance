package com.shanshui.performance.memory

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import com.shanshui.performance.ApplicationMetadata
import com.shanshui.performance.metrics.MemoryConfig
import com.shanshui.performance.metrics.MetricsServiceConfig
import com.shanshui.performance.core.AndroidNetworkTypeProvider
import com.shanshui.performance.identity.RuntimeIdentity
import com.shanshui.performance.network.MemoryMetricEvent
import com.shanshui.performance.network.MemoryPeakSummary
import com.shanshui.performance.network.MetricsNetworkClientFactory
import com.shanshui.performance.network.asMemoryBatchSender
import java.io.Closeable
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/**
 * 主进程内存采集器。
 *
 * Activity 回调只改变前后台状态，系统读取、落盘和网络发送分别运行在 SDK 自有线程，
 * 避免把 Debug.getPss、/proc 读取或磁盘 IO 放到主线程。
 */
internal class MemoryReporter private constructor(
    /** 宿主 Application，仅在主进程注册采集回调。 */
    private val application: Application,
    /** 共享环境及内存队列配置。 */
    private val config: MetricsServiceConfig,
    /** 前后台采样、检查点与上传批次配置。 */
    private val memoryConfig: MemoryConfig,
    /** 首次有效采样固定的原应用版本和包信息。 */
    private val metadata: ApplicationMetadata,
    /** 原运行构建标识，恢复及补报不替换。 */
    private val buildId: String,
    /** 当前进程共享的运行身份。 */
    private val runtimeIdentity: RuntimeIdentity,
    /** 原匿名设备标识，不在日志中输出。 */
    private val anonymousDeviceId: String,
    /** 只属于内存指标的快照与历史队列。 */
    private val queue: MemoryEventStore,
    /** 只选择已封存事件的上传器。 */
    private val uploader: MemoryUploader,
    /** 各项独立容错的系统内存读取器。 */
    private val sampler: MemorySampler,
    /** 所有 Reporter 接管共用的串行后台采样执行器。 */
    private val samplingScheduler: ScheduledExecutorService,
    /** 本实例独占的历史上传执行器。 */
    private val uploadScheduler: ScheduledExecutorService,
    /** 生成 Unix 毫秒观测时刻的时钟。 */
    private val clock: () -> Long,
) : Application.ActivityLifecycleCallbacks, Closeable {
    /** 本实例是否已经停止接收工作。 */
    private val closed = AtomicBoolean(false)
    /** 当前 started Activity 数，用于防止重建误判为后台。 */
    private val startedActivityCount = AtomicInteger(0)
    /** 当前采样上下文的前后台状态。 */
    private val foreground = AtomicBoolean(isProcessForeground())
    /** 当前 Activity 弱引用，不因采集延长其生命周期。 */
    private val currentActivity = AtomicReference<WeakReference<Activity>?>(null)
    /** 防止本实例重复进入上传循环。 */
    private val flushInProgress = AtomicBoolean(false)
    /** 三项系统读取同时不可用的次数。 */
    private val allMetricsUnavailableCount = AtomicInteger(0)
    /** 只在首个有效快照固定原网络上下文。 */
    private val networkProvider = AndroidNetworkTypeProvider(application)
    /** 本实例网络恢复回调，关闭时解绑。 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** 恢复/封存成功前禁止上传。 */
    private val restored = AtomicBoolean(false)
    /** 首次有效采样才固定事件，SDK 开始时间不伪称进程起点。 */
    private val collectionStartedAt = clock().coerceAtLeast(0L)
    /** 只由共享采样线程访问的本轮状态。 */
    private val recorder = MemoryRunRecorder(queue, runtimeIdentity.processId) { createRunEvent() }
    /** 本实例检查点句柄。 */
    private var checkpointFuture: ScheduledFuture<*>? = null
    /** 可独立验证的串行单次采样调度，不追赶延误。 */
    private val samplingLoop = MemorySamplingLoop(samplingScheduler, {
        if (foreground.get()) memoryConfig.foregroundSamplingIntervalMillis else memoryConfig.backgroundSamplingIntervalMillis
    }, { captureAndPersist() })

    init {
        try {
            samplingScheduler.execute { if (!closed.get()) restoreInBackground() }
            checkpointFuture = samplingScheduler.scheduleWithFixedDelay(
                { persistCheckpoint() }, memoryConfig.uploadIntervalMillis,
                memoryConfig.uploadIntervalMillis, TimeUnit.MILLISECONDS,
            )
            application.registerActivityLifecycleCallbacks(this)
            registerNetworkRecovery()
            samplingLoop.restart()
            uploadScheduler.schedule({ flushInBackground() }, 0L, TimeUnit.MILLISECONDS)
            uploadScheduler.scheduleAtFixedRate(
                { flushInBackground() },
                memoryConfig.uploadIntervalMillis,
                memoryConfig.uploadIntervalMillis,
                TimeUnit.MILLISECONDS,
            )
        } catch (failure: Throwable) {
            // 初始化回滚不能关闭进程共享执行器，也不能遗留本实例的周期写入。
            closed.set(true)
            checkpointFuture?.cancel(false)
            samplingLoop.close { }
            runCatching { application.unregisterActivityLifecycleCallbacks(this) }
            runCatching { unregisterNetworkCallback() }
            uploadScheduler.shutdownNow()
            throw failure
        }
    }

    /** 是否仍接受采集和上传请求。 */
    val isAvailable: Boolean
        get() = !closed.get()

    /** 返回已封存历史数，不包含当前轮。 */
    fun pendingEventCount(): Int = queue.count()

    /** 统计三项内存指标同时不可用的采样次数，便于诊断设备差异。 */
    internal fun allMetricsUnavailableCount(): Int = allMetricsUnavailableCount.get()

    /** 异步检查历史上传，不绕过退避。 */
    fun flushAsync() {
        if (!closed.get() && !uploadScheduler.isShutdown) {
            runCatching { uploadScheduler.execute { flushInBackground() } }
                .onFailure { if (!closed.get()) Log.w(TAG, "memory upload scheduling failed type=${it.javaClass.simpleName}") }
        }
    }

    /** 取消本实例回调和调度，并串行提交最后检查点。 */
    override fun close() {
        synchronized(Companion) {
            if (!closed.compareAndSet(false, true)) return
            runCatching { application.unregisterActivityLifecycleCallbacks(this) }
                .onFailure { Log.w(TAG, "memory lifecycle unregister failed type=${it.javaClass.simpleName}") }
            runCatching { unregisterNetworkCallback() }
                .onFailure { Log.w(TAG, "memory network unregister failed type=${it.javaClass.simpleName}") }
            checkpointFuture?.cancel(false)
            // 旧采样及最终检查点先执行完，新实例随后才在同一串行线程恢复。
            runCatching { samplingLoop.close { persistCheckpoint() } }
                .onFailure { Log.w(TAG, "memory final checkpoint scheduling failed type=${it.javaClass.simpleName}") }
            try { uploadScheduler.shutdownNow() } finally {
                if (activeReporter === this) activeReporter = null
            }
        }
    }

    /** 首个可见 Activity 进入前台，继续同一轮。 */
    override fun onActivityStarted(activity: Activity) {
        /** 当前可见 Activity 数量，不能用于划分运行轮次。 */
        val count = startedActivityCount.incrementAndGet()
        if (count == 1) setForeground(true)
    }

    /** 保存弱场景引用，不重置峰值。 */
    override fun onActivityResumed(activity: Activity) {
        currentActivity.set(WeakReference(activity))
    }

    /** 释放离场场景并延迟确认后台。 */
    override fun onActivityStopped(activity: Activity) {
        currentActivity.get()?.get()?.takeIf { it === activity }?.let {
            currentActivity.compareAndSet(currentActivity.get(), null)
        }
        /** 当前可见 Activity 数量，不能用于划分运行轮次。 */
        val count = decrementStartedActivityCount()
        if (count == 0 && !closed.get()) {
            // 旋转和页面切换期间可能短暂没有 started Activity，延迟确认后台状态。
            samplingScheduler.schedule({
                if (startedActivityCount.get() == 0) setForeground(false)
            }, BACKGROUND_CONFIRM_DELAY_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    /** 在不依赖 API 24 AtomicInteger 扩展的情况下安全减少前台 Activity 计数。 */
    private fun decrementStartedActivityCount(): Int {
        while (true) {
            // 读取当前值并把计数下限固定为零，避免生命周期回调重复触发时出现负数。
            val currentCount = startedActivityCount.get()
            /** 下限为零的下一次 Activity 数量。 */
            val nextCount = (currentCount - 1).coerceAtLeast(0)
            if (startedActivityCount.compareAndSet(currentCount, nextCount)) {
                return nextCount
            }
        }
    }

    /** 清理已销毁场景的弱引用。 */
    override fun onActivityDestroyed(activity: Activity) {
        currentActivity.get()?.get()?.takeIf { it === activity }?.let {
            currentActivity.set(null)
        }
    }

    /** 创建 Activity 不改变运行身份。 */
    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
    /** 暂停不封存当前轮。 */
    override fun onActivityPaused(activity: Activity) = Unit
    /** 状态保存不触发当前轮上传。 */
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

    /** 状态变化先检查点再立即采样，保留同轮峰值。 */
    private fun setForeground(value: Boolean) {
        if (closed.get() || foreground.getAndSet(value) == value) return
        samplingScheduler.execute { persistCheckpoint() }
        samplingLoop.restart()
    }

    /** 恢复成功后在串行线程读取、累计并持久化新高。 */
    private fun captureAndPersist() {
        if (closed.get()) return
        if (!restoreInBackground()) return
        /** 本次系统读取的三项独立原始字节值。 */
        val values = sampler.sample()
        if (!values.hasValue()) {
            /** 累计全部读取失败次数，用于受控诊断。 */
            val failureCount = allMetricsUnavailableCount.incrementAndGet()
            Log.w(TAG, "memory sample skipped: all metrics unavailable count=$failureCount")
            return
        }
        /** 本次观测固定的前后台上下文。 */
        val isForeground = foreground.get()
        /** 只保留合法前台 Activity 完整类名。 */
        val scene = if (isForeground) {
            currentActivity.get()?.get()?.javaClass?.name
                ?.takeIf { it.isNotBlank() && it.length <= MAX_SCENE_LENGTH && it.none(Char::isISOControl) }
        } else {
            null
        }
        runCatching { recorder.observe(values, clock(), isForeground, scene) }
            .onFailure { Log.w(TAG, "memory current persistence failed type=${it.javaClass.simpleName}") }
    }

    /** 在采样线程先恢复/封存，失败保留原 current 并在下次任务重试。 */
    private fun restoreInBackground(): Boolean {
        if (restored.get()) return true
        return runCatching { recorder.start() }
            .onSuccess { restored.set(true); flushAsync() }
            .onFailure { Log.w(TAG, "memory restore/seal failed type=${it.javaClass.simpleName}") }
            .isSuccess
    }

    /** 周期与 close 提交最近有效观测截止，不入队当前轮。 */
    private fun persistCheckpoint() {
        if (!restoreInBackground()) return
        runCatching { recorder.checkpoint() }
            .onFailure { Log.w(TAG, "memory checkpoint failed type=${it.javaClass.simpleName}") }
    }

    /** 首次有效采样固定原身份和元数据，后续恢复不重新构造。 */
    private fun createRunEvent(): MemoryMetricEvent = MemoryMetricEvent(
            schemaVersion = MEMORY_SCHEMA_VERSION,
            eventId = UUID.randomUUID().toString(),
            eventType = EVENT_TYPE,
            occurredAt = collectionStartedAt,
            sessionId = runtimeIdentity.sessionId,
            processId = runtimeIdentity.processId,
            anonymousDeviceId = anonymousDeviceId.take(MAX_DEVICE_ID_LENGTH),
            packageName = metadata.packageName,
            appVersion = metadata.versionName,
            versionCode = metadata.versionCode,
            buildId = buildId,
            environment = config.environment,
            channel = config.channel,
            osVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" },
            deviceModel = Build.MODEL.orEmpty().ifBlank { "unknown" },
            networkType = networkProvider.invoke(),
            memoryPeakSummary = MemoryPeakSummary(
                processName = processName(application),
                collectionStartedAt = collectionStartedAt,
                lastSampledAt = collectionStartedAt,
            ),
        )

    /** 只在恢复后上传已封存队列，每实例防止重入。 */
    private fun flushInBackground() {
        if (closed.get() || !restored.get() || !flushInProgress.compareAndSet(false, true)) return
        try {
            runBlocking { uploader.flush() }
        } catch (throwable: Throwable) {
            Log.w(TAG, "memory upload failed type=${throwable.javaClass.simpleName}")
        } finally {
            flushInProgress.set(false)
        }
    }

    /** 网络恢复时触发历史上传。 */
    private fun registerNetworkRecovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        /** 宿主提供的连接管理服务。 */
        val manager = application.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        /** 待注册或解绑的网络恢复回调。 */
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                flushAsync()
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure {
            Log.w(TAG, "unable to register memory network callback type=${it.javaClass.simpleName}")
        }
    }

    /** 解绑本实例网络回调。 */
    private fun unregisterNetworkCallback() {
        /** 待注册或解绑的网络恢复回调。 */
        val callback = networkCallback ?: return
        /** 宿主提供的连接管理服务。 */
        val manager = application.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    companion object {
        /** 唯一采集所有者；关闭排入最终检查点后才能交给下一实例。 */
        private var activeReporter: MemoryReporter? = null
        /** 同进程 Reporter 共用一个采样线程，旧任务退出后新实例才可接管。 */
        private val serialSamplingScheduler = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "memory-sampling").apply { isDaemon = true }
        }.apply {
            // 执行器跨实例复用，取消的旧周期不应留在共享延迟队列中。
            removeOnCancelPolicy = true
        }
        /** 内存 Reporter 日志标签。 */
        private const val TAG = "MemoryReporter"
        /** 内存指标独立目录名，不能清理其他组件。 */
        private const val QUEUE_DIRECTORY = "performance-memory-reporter"
        /** 唯一支持的运行峰值事件类型。 */
        private const val EVENT_TYPE = "memory_peak_summary"
        /** 与统一批量入口一致的事件 schema 版本。 */
        private const val MEMORY_SCHEMA_VERSION = 2
        /** 服务端允许的 Activity 场景最大字符数。 */
        private const val MAX_SCENE_LENGTH = 128
        /** 匿名设备标识最大字符数。 */
        private const val MAX_DEVICE_ID_LENGTH = 256
        /** 短暂 Activity 切换后确认后台的等待。 */
        private const val BACKGROUND_CONFIRM_DELAY_MILLIS = 700L
        /** 前台进程对应的系统重要性值。 */
        private const val MAIN_PROCESS_IMPORTANCE = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        /** 限制主进程拥有内存指标目录。 */
        fun isProcessAvailable(application: Application): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return application.packageName == Application.getProcessName()
            }
            /** 当前 Android 进程的系统状态。 */
            val processInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(processInfo)
            return application.packageName == processInfo.processName
        }

        /** 装配 Reporter，调用线程不读取或写入快照。 */
        @Synchronized
        fun create(
            application: Application,
            config: MetricsServiceConfig,
            memoryConfig: MemoryConfig,
            metadata: ApplicationMetadata,
            buildId: String,
            runtimeIdentity: RuntimeIdentity,
            anonymousDeviceId: String,
            networkFactory: MetricsNetworkClientFactory,
        ): MemoryReporter {
            require(isProcessAvailable(application)) { "memory reporter requires main process" }
            check(activeReporter == null) { "memory reporter already owns current snapshot" }
            /** 宿主应用上下文，用于定位不备份目录。 */
            val context = application.applicationContext
            /** 内存指标专属的不备份根目录。 */
            val root = File(context.noBackupFilesDir, QUEUE_DIRECTORY)
            /** 只属于内存指标的快照与历史队列。 */
            val queue = MemoryEventStore(
                root = root,
                diskQuotaBytes = config.memoryQueueDiskQuotaBytes,
                eventTtlMillis = config.memoryEventTtlMillis,
                maxAttempts = config.memoryMaxAttempts,
                logger = { message -> Log.i(TAG, message()) },
            )
            /** 只选择已封存事件的上传器。 */
            val uploader = MemoryUploader(
                store = queue,
                sender = networkFactory.memoryNetworkClient.asMemoryBatchSender(),
                config = memoryConfig,
                logger = { message -> Log.i(TAG, message()) },
            )
            /** 所有 Reporter 接管共用的串行后台采样执行器。 */
            val samplingScheduler = serialSamplingScheduler
            /** 本实例独占的历史上传执行器。 */
            val uploadScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "memory-upload").apply { isDaemon = true }
            }
            return try {
                MemoryReporter(
                    application = application,
                    config = config,
                    memoryConfig = memoryConfig,
                    metadata = metadata,
                    buildId = buildId,
                    runtimeIdentity = runtimeIdentity,
                    anonymousDeviceId = anonymousDeviceId,
                    queue = queue,
                    uploader = uploader,
                    sampler = MemorySampler(),
                    samplingScheduler = samplingScheduler,
                    uploadScheduler = uploadScheduler,
                    clock = { System.currentTimeMillis() },
                ).also { activeReporter = it }
            } catch (throwable: Throwable) {
                uploadScheduler.shutdownNow()
                throw throwable
            }
        }

        /** 读取原主进程名称并限制长度。 */
        private fun processName(application: Application): String {
            /** Android 提供的原主进程名称。 */
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                /** 当前 Android 进程重要性或进程名。 */
                val info = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(info)
                info.processName
            }
            return name.orEmpty().ifBlank { application.packageName }.take(256)
        }

        /** 从进程重要性推断初始状态。 */
        private fun isProcessForeground(): Boolean {
            /** 当前 Android 进程重要性或进程名。 */
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            return info.importance == MAIN_PROCESS_IMPORTANCE
        }
    }
}
