package com.example.nativelib.memory.leak

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import com.example.nativelib.ApplicationMetadata
import com.example.nativelib.config.NativeServiceConfig
import com.example.nativelib.network.MemoryLeakReportMetadata
import com.example.nativelib.network.NetworkClientFactory
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * KOOM report 回调的持久化和上传生命周期。
 *
 * 回调线程只负责生成 metadata、原子落盘和调度；网络 IO 在专用线程执行，避免阻塞 KOOM 回调。
 */
internal class MemoryLeakReportReporter private constructor(
    private val context: Context,
    private val metadata: ApplicationMetadata,
    private val environment: String,
    private val channel: String,
    private val buildId: String,
    private val anonymousDeviceId: String,
    private val queue: MemoryLeakReportStore,
    private val uploader: MemoryLeakReportUploader,
    private val scheduler: ScheduledExecutorService,
    private val clock: () -> Long,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val sessionId = UUID.randomUUID().toString()
    private val processName = resolveProcessName(context)
    private val scheduleLock = Any()
    private var scheduledFlush: ScheduledFuture<*>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        try {
            registerNetworkRecovery()
            scheduleFlush(0L)
        } catch (throwable: Throwable) {
            unregisterNetworkCallback()
            scheduler.shutdownNow()
            throw throwable
        }
    }

    /** 报告内容先安全入队，入队成功后才能删除 KOOM 原始 JSON。 */
    fun enqueueReport(file: File, content: String): Boolean {
        if (closed.get()) return false
        val eventId = UUID.randomUUID().toString()
        val reportMetadata = MemoryLeakReportMetadata(
            schemaVersion = REPORT_SCHEMA_VERSION,
            eventId = eventId,
            occurredAt = clock(),
            packageName = metadata.packageName,
            appVersion = metadata.versionName,
            versionCode = metadata.versionCode,
            anonymousDeviceId = anonymousDeviceId,
            processName = processName,
            sessionId = sessionId,
            buildId = buildId,
            environment = environment,
            channel = channel,
        )
        val queued = runCatching {
            queue.enqueue(reportMetadata, content, reportMetadata.occurredAt)
        }.onFailure {
            Log.w(TAG, "unable to persist memory leak report type=${it.javaClass.simpleName}")
        }.getOrDefault(false)
        if (!queued) return false

        if (file.exists() && !file.delete()) {
            Log.w(TAG, "unable to delete uploaded memory leak report file=${file.name}")
        }
        scheduleFlush(0L)
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        unregisterNetworkCallback()
        synchronized(scheduleLock) {
            scheduledFlush?.cancel(false)
            scheduledFlush = null
        }
        scheduler.shutdownNow()
    }

    private fun scheduleFlush(delayMillis: Long) {
        if (closed.get() || scheduler.isShutdown) return
        synchronized(scheduleLock) {
            val current = scheduledFlush
            if (current != null && !current.isDone) {
                val currentDelay = current.getDelay(TimeUnit.MILLISECONDS)
                if (currentDelay <= delayMillis) return
                current.cancel(false)
            }
            scheduledFlush = scheduler.schedule(
                { flushInBackground() },
                delayMillis.coerceAtLeast(0L),
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun flushInBackground() {
        synchronized(scheduleLock) {
            scheduledFlush = null
        }
        if (closed.get()) return
        try {
            runBlocking { uploader.flush() }
        } catch (throwable: Throwable) {
            Log.w(TAG, "memory leak report upload cycle failed type=${throwable.javaClass.simpleName}")
        } finally {
            if (!closed.get()) {
                val nextAttempt = queue.nextAttemptAtMillis()
                val delay = nextAttempt
                    ?.minus(clock())
                    ?.coerceAtLeast(0L)
                    ?.coerceAtMost(MemoryLeakReportLimits.UPLOAD_INTERVAL_MILLIS)
                    ?: MemoryLeakReportLimits.UPLOAD_INTERVAL_MILLIS
                scheduleFlush(delay)
            }
        }
    }

    private fun registerNetworkRecovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleFlush(0L)
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure {
            Log.w(TAG, "unable to register memory leak report network callback")
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    companion object {
        private const val TAG = "MemoryLeakReportReporter"
        private const val REPORT_SCHEMA_VERSION = 1
        private const val QUEUE_DIRECTORY = "performance-memory-leak-reporter"
        private const val MAIN_PROCESS_IMPORTANCE =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

        /** 由 SDK 初始化阶段创建，使用共享 OkHttp/Retrofit 客户端。 */
        fun create(
            application: Application,
            serviceConfig: NativeServiceConfig,
            metadata: ApplicationMetadata,
            buildId: String,
            anonymousDeviceId: String,
            networkFactory: NetworkClientFactory,
        ): MemoryLeakReportReporter {
            val context = application.applicationContext
            val root = File(context.noBackupFilesDir, QUEUE_DIRECTORY)
            val queue = MemoryLeakReportStore(
                root = root,
                diskQuotaBytes = MemoryLeakReportLimits.QUEUE_DISK_QUOTA_BYTES,
                logger = { message -> Log.i(TAG, message()) },
            )
            val uploader = MemoryLeakReportUploader(
                store = queue,
                sender = MemoryLeakReportSender { reportMetadata, reportFile ->
                    networkFactory.memoryLeakReportNetworkClient.upload(reportMetadata, reportFile)
                },
                logger = { message -> Log.i(TAG, message()) },
            )
            val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "memory-leak-report-upload").apply { isDaemon = true }
            }
            return try {
                MemoryLeakReportReporter(
                    context = context,
                    metadata = metadata,
                    environment = serviceConfig.environment,
                    channel = serviceConfig.channel,
                    buildId = buildId,
                    anonymousDeviceId = anonymousDeviceId,
                    queue = queue,
                    uploader = uploader,
                    scheduler = scheduler,
                    clock = { System.currentTimeMillis() },
                )
            } catch (throwable: Throwable) {
                scheduler.shutdownNow()
                throw throwable
            }
        }

        private fun resolveProcessName(context: Context): String {
            val application = context.applicationContext as Application
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                val info = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(info)
                info.processName
            }
            return name.orEmpty().ifBlank { application.packageName }.take(256)
        }
    }
}
