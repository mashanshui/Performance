package com.example.nativelib.memory

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import com.example.nativelib.ApplicationMetadata
import com.example.nativelib.MemoryConfig
import com.example.nativelib.config.NativeServiceConfig
import com.example.nativelib.crash.AndroidNetworkTypeProvider
import com.example.nativelib.identity.RuntimeIdentity
import com.example.nativelib.network.MemoryMetricEvent
import com.example.nativelib.network.NetworkClientFactory
import com.example.nativelib.network.asMemoryBatchSender
import java.io.Closeable
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
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
    private val application: Application,
    private val config: NativeServiceConfig,
    private val memoryConfig: MemoryConfig,
    private val metadata: ApplicationMetadata,
    private val buildId: String,
    /** 当前进程共享的运行身份。 */
    private val runtimeIdentity: RuntimeIdentity,
    private val anonymousDeviceId: String,
    private val queue: MemoryEventStore,
    private val uploader: MemoryUploader,
    private val sampler: MemorySampler,
    private val samplingScheduler: ScheduledExecutorService,
    private val uploadScheduler: ScheduledExecutorService,
    private val clock: () -> Long,
) : Application.ActivityLifecycleCallbacks, Closeable {
    private val closed = AtomicBoolean(false)
    private val startedActivityCount = AtomicInteger(0)
    private val foreground = AtomicBoolean(isProcessForeground())
    private val currentActivity = AtomicReference<WeakReference<Activity>?>(null)
    private val flushInProgress = AtomicBoolean(false)
    private val samplingGeneration = AtomicInteger(0)
    private val allMetricsUnavailableCount = AtomicInteger(0)
    private val networkProvider = AndroidNetworkTypeProvider(application)
    private var samplingFuture: ScheduledFuture<*>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        application.registerActivityLifecycleCallbacks(this)
        registerNetworkRecovery()
        scheduleSampling(initial = true)
        uploadScheduler.schedule({ flushInBackground() }, 0L, TimeUnit.MILLISECONDS)
        uploadScheduler.scheduleAtFixedRate(
            { flushInBackground() },
            memoryConfig.uploadIntervalMillis,
            memoryConfig.uploadIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    val isAvailable: Boolean
        get() = !closed.get()

    fun pendingEventCount(): Int = queue.count()

    /** 统计三项内存指标同时不可用的采样次数，便于诊断设备差异。 */
    internal fun allMetricsUnavailableCount(): Int = allMetricsUnavailableCount.get()

    fun flushAsync() {
        if (!closed.get() && !uploadScheduler.isShutdown) {
            uploadScheduler.execute { flushInBackground() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        application.unregisterActivityLifecycleCallbacks(this)
        unregisterNetworkCallback()
        samplingFuture?.cancel(false)
        samplingScheduler.shutdownNow()
        uploadScheduler.shutdownNow()
    }

    override fun onActivityStarted(activity: Activity) {
        val count = startedActivityCount.incrementAndGet()
        if (count == 1) setForeground(true)
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity.set(WeakReference(activity))
    }

    override fun onActivityStopped(activity: Activity) {
        currentActivity.get()?.get()?.takeIf { it === activity }?.let {
            currentActivity.compareAndSet(currentActivity.get(), null)
        }
        val count = startedActivityCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (count == 0 && !closed.get()) {
            // 旋转和页面切换期间可能短暂没有 started Activity，延迟确认后台状态。
            samplingScheduler.schedule({
                if (startedActivityCount.get() == 0) setForeground(false)
            }, BACKGROUND_CONFIRM_DELAY_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        currentActivity.get()?.get()?.takeIf { it === activity }?.let {
            currentActivity.set(null)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

    private fun setForeground(value: Boolean) {
        if (closed.get() || foreground.getAndSet(value) == value) return
        scheduleSampling(initial = true)
    }

    private fun scheduleSampling(initial: Boolean) {
        if (samplingScheduler.isShutdown) return
        samplingFuture?.cancel(false)
        val generation = samplingGeneration.incrementAndGet()
        val interval = if (foreground.get()) {
            memoryConfig.foregroundSamplingIntervalMillis
        } else {
            memoryConfig.backgroundSamplingIntervalMillis
        }
        val initialDelay = if (initial) 0L else interval
        scheduleSamplingTask(generation, initialDelay, interval)
    }

    /** 使用单次调度重新安排下一次采样，避免任务延迟后追赶执行错过的周期。 */
    private fun scheduleSamplingTask(generation: Int, delayMillis: Long, intervalMillis: Long) {
        samplingFuture = samplingScheduler.schedule({
            if (closed.get() || samplingGeneration.get() != generation) return@schedule
            captureAndPersist()
            if (!closed.get() && samplingGeneration.get() == generation) {
                scheduleSamplingTask(generation, intervalMillis, intervalMillis)
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun captureAndPersist() {
        if (closed.get()) return
        val values = sampler.sample()
        if (!values.hasValue()) {
            val failureCount = allMetricsUnavailableCount.incrementAndGet()
            Log.w(TAG, "memory sample skipped: all metrics unavailable count=$failureCount")
            return
        }
        val processName = processName(application)
        val isForeground = foreground.get()
        val scene = if (isForeground) {
            currentActivity.get()?.get()?.javaClass?.name
                ?.takeIf { it.isNotBlank() && it.length <= MAX_SCENE_LENGTH && it.none(Char::isISOControl) }
        } else {
            null
        }
        val event = MemoryMetricEvent(
            schemaVersion = MEMORY_SCHEMA_VERSION,
            eventId = UUID.randomUUID().toString(),
            eventType = EVENT_TYPE,
            occurredAt = clock(),
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
            memorySample = com.example.nativelib.network.MemorySamplePayload(
                pssBytes = values.pssBytes,
                vssBytes = values.vssBytes,
                javaHeapUsedBytes = values.javaHeapUsedBytes,
                processName = processName,
                foreground = isForeground,
                scene = scene,
            ),
        )
        runCatching { queue.enqueue(event) }
            .onSuccess { flushAsync() }
            .onFailure { Log.w(TAG, "memory event persistence failed type=${it.javaClass.simpleName}") }
    }

    private fun flushInBackground() {
        if (closed.get() || !flushInProgress.compareAndSet(false, true)) return
        try {
            runBlocking { uploader.flush() }
        } catch (throwable: Throwable) {
            Log.w(TAG, "memory upload failed type=${throwable.javaClass.simpleName}")
        } finally {
            flushInProgress.set(false)
        }
    }

    private fun registerNetworkRecovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val manager = application.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
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

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val manager = application.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    companion object {
        private const val TAG = "MemoryReporter"
        private const val QUEUE_DIRECTORY = "performance-memory-reporter"
        private const val EVENT_TYPE = "memory_sample"
        private const val MEMORY_SCHEMA_VERSION = 2
        private const val MAX_SCENE_LENGTH = 128
        private const val MAX_DEVICE_ID_LENGTH = 256
        private const val BACKGROUND_CONFIRM_DELAY_MILLIS = 700L
        private const val MAIN_PROCESS_IMPORTANCE = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        fun isProcessAvailable(application: Application): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return application.packageName == Application.getProcessName()
            }
            val processInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(processInfo)
            return application.packageName == processInfo.processName
        }

        fun create(
            application: Application,
            config: NativeServiceConfig,
            memoryConfig: MemoryConfig,
            metadata: ApplicationMetadata,
            buildId: String,
            runtimeIdentity: RuntimeIdentity,
            anonymousDeviceId: String,
            networkFactory: NetworkClientFactory,
        ): MemoryReporter {
            val context = application.applicationContext
            val root = File(context.noBackupFilesDir, QUEUE_DIRECTORY)
            val queue = MemoryEventStore(
                root = root,
                diskQuotaBytes = config.memoryQueueDiskQuotaBytes,
                eventTtlMillis = config.memoryEventTtlMillis,
                maxAttempts = config.memoryMaxAttempts,
                logger = { message -> Log.i(TAG, message()) },
            )
            val uploader = MemoryUploader(
                store = queue,
                sender = networkFactory.memoryNetworkClient.asMemoryBatchSender(),
                config = memoryConfig,
                logger = { message -> Log.i(TAG, message()) },
            )
            val samplingScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "memory-sampling").apply { isDaemon = true }
            }
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
                )
            } catch (throwable: Throwable) {
                samplingScheduler.shutdownNow()
                uploadScheduler.shutdownNow()
                throw throwable
            }
        }

        private fun processName(application: Application): String {
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                val info = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(info)
                info.processName
            }
            return name.orEmpty().ifBlank { application.packageName }.take(256)
        }

        private fun isProcessForeground(): Boolean {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            return info.importance == MAIN_PROCESS_IMPORTANCE
        }
    }
}
