package com.example.nativelib.jank

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import com.bytedance.rheatrace.RheaTrace3
import com.example.nativelib.config.NativeServiceConfig
import com.example.nativelib.network.NetworkClientFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/** RheaTrace3 线上采集与卡顿 ZIP 持久上传的统一初始化结果。 */
data class JankArtifactInitResult(
    val traceResult: RheaTrace3.InitResult,
    val reporterReady: Boolean,
    val errorCode: String? = null,
) {
    val isReady: Boolean
        get() = reporterReady

    override fun toString(): String {
        return "JankArtifactInitResult(" +
            "traceResult=$traceResult, " +
            "reporterReady=$reporterReady, " +
            "errorCode=${errorCode ?: "none"})"
    }
}

/** 导出结果和是否已写入持久上传队列。 */
data class JankArtifactExportResult(
    val exportResult: RheaTrace3.ExportResult,
    val queued: Boolean,
)

fun interface JankArtifactExportCallback {
    /** 回调运行在 RheaTrace3 的导出线程，不会自动切换到主线程。 */
    fun onCompleted(result: JankArtifactExportResult)
}

class JankArtifactReporter private constructor(
    private val context: Context,
    private val config: JankUploadConfig,
    private val queue: FileJankUploadQueue,
    private val artifactStore: JankArtifactStore,
    private val uploader: JankArtifactUploader,
    private val scheduler: ScheduledExecutorService,
    private val traceAdapter: JankTraceAdapter,
) {
    private val scheduleLock = Any()
    private var scheduledFlush: ScheduledFuture<*>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        // 初始化时先把 RheaTrace 已生成但尚未确认的 ZIP 纳入持久队列。
        queue.reconcile(artifactStore.pendingArtifacts())
        registerNetworkRecovery()
        scheduleFlush(0)
    }

    private fun enqueueArtifact(artifact: File): Boolean {
        return runCatching {
            if (!artifact.isFile || !FileJankUploadQueue.validArtifactName(artifact.name)) {
                logSafe(artifact, "INVALID_ARTIFACT", "ignored")
                return@runCatching false
            }
            val pending = artifactStore.pendingArtifacts()
            val trustedArtifact = pending.firstOrNull { candidate -> sameFile(candidate, artifact) }
            if (trustedArtifact == null) {
                logSafe(artifact, "NOT_PENDING_ARTIFACT", "ignored")
                return@runCatching false
            }
            // reconcile 会幂等地创建记录，因此重复导出或重复回调仍然只保留一条队列记录。
            queue.reconcile(pending)
            val eventId = FileJankUploadQueue.eventIdFromFileName(trustedArtifact.name)
            if (queue.find(eventId) == null) {
                logSafe(trustedArtifact, "QUEUE_RECORD_MISSING", "failed")
                return@runCatching false
            }
            scheduleFlush(0)
            true
        }.getOrElse {
            logSafe(artifact, "ENQUEUE_FAILED", "failed")
            false
        }
    }

    private fun scheduleImmediateFlush() {
        scheduleFlush(0)
    }

    private fun pendingCount(): Int = queue.count()

    private fun closeInternal() {
        unregisterNetworkCallback()
        synchronized(scheduleLock) {
            scheduledFlush?.cancel(false)
            scheduledFlush = null
        }
        scheduler.shutdownNow()
        instance.compareAndSet(this, null)
    }

    private fun scheduleFlush(delayMillis: Long) {
        if (scheduler.isShutdown) {
            return
        }
        synchronized(scheduleLock) {
            val current = scheduledFlush
            if (current != null && !current.isDone) {
                val currentDelay = current.getDelay(TimeUnit.MILLISECONDS)
                if (currentDelay <= delayMillis) {
                    return
                }
                current.cancel(false)
            }
            scheduledFlush = scheduler.schedule(
                { flushInBackground() },
                delayMillis.coerceAtLeast(0),
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun flushInBackground() {
        synchronized(scheduleLock) {
            scheduledFlush = null
        }
        try {
            runBlocking { uploader.flush() }
        } catch (throwable: Throwable) {
            Log.w(TAG, "jank upload cycle failed: ${throwable.javaClass.simpleName}")
        } finally {
            val now = System.currentTimeMillis()
            val nextAttempt = queue.nextAttemptAtMillis()
            val delay = nextAttempt
                ?.minus(now)
                ?.coerceAtLeast(0)
                ?.coerceAtMost(config.uploadIntervalMillis)
                ?: config.uploadIntervalMillis
            scheduleFlush(delay)
        }
    }

    private fun registerNetworkRecovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleImmediateFlush()
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure {
            Log.w(TAG, "unable to register network callback: ${it.javaClass.simpleName}")
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    private fun sameFile(first: File, second: File): Boolean {
        return runCatching { first.canonicalFile == second.canonicalFile }.getOrDefault(false)
    }

    private fun logSafe(artifact: File, code: String, outcome: String) {
        val eventId = artifact.name
            .takeIf { fileName -> FileJankUploadQueue.validArtifactName(fileName) }
            ?.let { fileName -> FileJankUploadQueue.eventIdFromFileName(fileName) }
            ?: "unknown"
        Log.w(TAG, "jank artifact: eventId=$eventId code=$code outcome=$outcome")
    }

    companion object {
        private const val TAG = "JankArtifactReporter"
        private const val QUEUE_DIRECTORY = "performance-jank-reporter"
        private const val ERROR_REPORTER_INIT_FAILED = "REPORTER_INIT_FAILED"
        private const val ERROR_TRACE_INIT_FAILED = "RHEA_INIT_FAILED"
        private const val ERROR_REPORTING_DISABLED = "REPORTING_DISABLED"
        private const val ERROR_TRACE_NOT_STARTED = "TRACE_NOT_STARTED"
        private val instance = AtomicReference<JankArtifactReporter?>()

        /** 由 PerformanceSdk 调用，使用统一配置和共享网络客户端启动 Jank Reporter。 */
        internal fun initialize(
            application: Application,
            serviceConfig: NativeServiceConfig,
            networkFactory: NetworkClientFactory,
            onlineTraceConfig: RheaTrace3.OnlineTraceConfig,
        ): JankArtifactInitResult {
            instance.get()?.let {
                return JankArtifactInitResult(
                    traceResult = RheaTrace3.InitResult.ALREADY_STARTED,
                    reporterReady = true,
                )
            }
            synchronized(instance) {
                instance.get()?.let {
                    return JankArtifactInitResult(
                        traceResult = RheaTrace3.InitResult.ALREADY_STARTED,
                        reporterReady = true,
                    )
                }

                if (!serviceConfig.jankEnabled) {
                    return JankArtifactInitResult(
                        traceResult = RheaTrace3.InitResult.DISABLED,
                        reporterReady = false,
                        errorCode = ERROR_REPORTING_DISABLED,
                    )
                }

                val effectiveTraceConfig = onlineTraceConfig
                val traceAdapter = JankTraceAdapterProvider.current
                val traceResult = runCatching {
                    traceAdapter.initOnline(application, effectiveTraceConfig)
                }.getOrElse {
                    // 不把异常对象直接写入日志，避免底层实现错误信息意外带出敏感配置。
                    Log.e(TAG, "online trace initialization failed: ${it.javaClass.simpleName}")
                    return JankArtifactInitResult(
                        traceResult = RheaTrace3.InitResult.NATIVE_INIT_FAILED,
                        reporterReady = false,
                        errorCode = ERROR_TRACE_INIT_FAILED,
                    )
                }
                if (traceResult != RheaTrace3.InitResult.STARTED &&
                    traceResult != RheaTrace3.InitResult.ALREADY_STARTED
                ) {
                    return JankArtifactInitResult(
                        traceResult = traceResult,
                        reporterReady = false,
                        errorCode = ERROR_TRACE_NOT_STARTED,
                    )
                }

                val reporter = runCatching {
                    createReporter(application, serviceConfig, networkFactory, traceAdapter)
                }.getOrElse {
                    Log.e(TAG, "jank reporter initialization failed: ${it.javaClass.simpleName}")
                    return JankArtifactInitResult(
                        traceResult = traceResult,
                        reporterReady = false,
                        errorCode = ERROR_REPORTER_INIT_FAILED,
                    )
                }
                instance.set(reporter)
                return JankArtifactInitResult(
                    traceResult = traceResult,
                    reporterReady = true,
                )
            }
        }

        /** 导出指定卡顿事件并在导出成功后写入持久上传队列。 */
        @JvmStatic
        fun exportAndEnqueue(
            event: RheaTrace3.JankEvent,
            callback: JankArtifactExportCallback,
        ): RheaTrace3.ExportRequestResult {
            val reporter = instance.get()
                ?: return RheaTrace3.ExportRequestResult.NOT_INITIALIZED
            return runCatching {
                reporter.traceAdapter.exportJankTrace(
                    event,
                    RheaTrace3.ExportCallback { exportResult ->
                        val artifact = exportResult.artifact.takeIf {
                            exportResult.status == RheaTrace3.ExportStatus.SUCCESS ||
                                exportResult.status == RheaTrace3.ExportStatus.PARTIAL
                        }
                        val queued = artifact?.let(reporter::enqueueArtifact) ?: false
                        runCatching {
                            callback.onCompleted(
                                JankArtifactExportResult(
                                    exportResult = exportResult,
                                    queued = queued,
                                ),
                            )
                        }.onFailure {
                            Log.w(TAG, "jank export callback failed: ${it.javaClass.simpleName}")
                        }
                    },
                )
            }.getOrElse {
                Log.w(TAG, "jank export request failed: ${it.javaClass.simpleName}")
                RheaTrace3.ExportRequestResult.STORAGE_UNAVAILABLE
            }
        }

        @JvmStatic
        fun flushAsync(): Boolean {
            val reporter = instance.get() ?: return false
            reporter.scheduleImmediateFlush()
            return true
        }

        @JvmStatic
        fun pendingArtifactCount(): Int = instance.get()?.pendingCount() ?: 0

        @JvmStatic
        fun shutdown() {
            close()
        }

        /** 关闭卡顿采集关联的队列、调度器和网络恢复监听。 */
        @JvmStatic
        fun close() {
            instance.get()?.closeInternal()
        }

        private fun createReporter(
            application: Application,
            serviceConfig: NativeServiceConfig,
            networkFactory: NetworkClientFactory,
            traceAdapter: JankTraceAdapter,
        ): JankArtifactReporter {
            val applicationContext = application.applicationContext
            val queue = FileJankUploadQueue(
                File(applicationContext.noBackupFilesDir, QUEUE_DIRECTORY),
            )
            val artifactStore = object : JankArtifactStore {
                override fun pendingArtifacts(): List<File> {
                    return traceAdapter.pendingJankFiles()
                }

                override fun delete(artifact: File): Boolean {
                    return traceAdapter.deleteJankFile(artifact)
                }
            }
            val config = JankUploadConfig(
                uploadIntervalMillis = serviceConfig.jankUploadIntervalMillis,
                connectTimeoutMillis = serviceConfig.connectTimeoutMillis,
                readTimeoutMillis = serviceConfig.readTimeoutMillis,
                writeTimeoutMillis = serviceConfig.writeTimeoutMillis,
                maxArtifactBytes = serviceConfig.jankMaxArtifactBytes,
                enableNetworkLogging = serviceConfig.enableNetworkLogging,
            )
            val uploader = JankArtifactUploader(
                queue = queue,
                store = artifactStore,
                sender = JankArtifactSender { artifact ->
                    networkFactory.jankArtifactNetworkClient.upload(artifact)
                },
                config = config,
                logger = { message -> Log.w(TAG, message) },
            )
            val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "jank-artifact-upload").apply { isDaemon = true }
            }
            return try {
                JankArtifactReporter(
                    context = applicationContext,
                    config = config,
                    queue = queue,
                    artifactStore = artifactStore,
                    uploader = uploader,
                    scheduler = scheduler,
                    traceAdapter = traceAdapter,
                )
            } catch (throwable: Throwable) {
                scheduler.shutdownNow()
                throw throwable
            }
        }

    }
}
