package com.shanshui.performance.crash

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import com.shanshui.performance.core.AndroidNetworkTypeProvider
import com.shanshui.performance.core.DeviceIdentityStore
import com.shanshui.performance.identity.RuntimeIdentity
import com.shanshui.performance.network.CrashNetworkClientFactory
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal class CrashReporter private constructor(
    private val context: Context,
    private val config: CrashReporterConfig,
    private val serviceConfig: CrashServiceConfig,
    private val queue: FileCrashQueue,
    private val eventFactory: CrashEventFactory,
    private val uploader: CrashUploader,
    private val scheduler: ScheduledExecutorService,
    private val previousExceptionHandler: Thread.UncaughtExceptionHandler?,
) : Closeable {
    private val flushInProgress = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val uncaughtExceptionHandler = object : Thread.UncaughtExceptionHandler {
        private val handling = AtomicBoolean(false)

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            if (handling.compareAndSet(false, true)) {
                captureAndFlush(throwable)
            }
            delegateToPreviousHandler(thread, throwable)
        }
    }

    init {
        start()
    }

    fun flushAsync() {
        if (scheduler.isShutdown) {
            return
        }
        scheduler.execute { flushInBackground() }
    }

    fun pendingEventCount(): Int = queue.count()

    override fun close() {
        unregisterNetworkCallback()
        scheduler.shutdownNow()
        if (Thread.getDefaultUncaughtExceptionHandler() === uncaughtExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousExceptionHandler)
        }
    }

    private fun start() {
        runCatching {
            queue.enqueue(eventFactory.appStart())
        }.onFailure {
            log("unable to persist app_start: ${it.javaClass.simpleName}")
        }
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler)
        registerNetworkRecovery()
        scheduler.schedule({ flushInBackground() }, 0, TimeUnit.MILLISECONDS)
        scheduler.scheduleAtFixedRate(
            { flushInBackground() },
            serviceConfig.crashUploadIntervalMillis,
            serviceConfig.crashUploadIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun captureAndFlush(throwable: Throwable) {
        runCatching {
            val event = eventFactory.crash(throwable)
            queue.enqueue(event)
            flushSynchronously()
        }.onFailure {
            log("unable to persist or upload crash: ${it.javaClass.simpleName}")
        }
    }

    private fun flushInBackground() {
        if (!flushInProgress.compareAndSet(false, true)) {
            return
        }
        try {
            runBlocking {
                uploader.flush()
            }
        } catch (throwable: Throwable) {
            log("background crash upload failed: ${throwable.javaClass.simpleName}")
        } finally {
            flushInProgress.set(false)
        }
    }

    private fun flushSynchronously() {
        if (!flushInProgress.compareAndSet(false, true)) {
            return
        }
        try {
            runBlocking {
                withTimeout(CRASH_HANDLER_FLUSH_TIMEOUT_MILLIS) {
                    uploader.flush(maxBatches = 1)
                }
            }
        } finally {
            flushInProgress.set(false)
        }
    }

    private fun delegateToPreviousHandler(thread: Thread, throwable: Throwable) {
        try {
            previousExceptionHandler?.uncaughtException(thread, throwable)
                ?: Runtime.getRuntime().exit(1)
        } catch (_: Throwable) {
            Runtime.getRuntime().exit(1)
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
                flushAsync()
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure {
            log("unable to register network callback: ${it.javaClass.simpleName}")
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    private fun log(message: String) {
        Log.w(TAG, message)
    }

    companion object {
        private const val TAG = "CrashReporter"
        internal const val QUEUE_DIRECTORY = "performance-crash-reporter"
        private const val CRASH_HANDLER_FLUSH_TIMEOUT_MILLIS = 3_500L

        /** 由 PerformanceSdk 调用，使用统一初始化阶段创建 Crash Reporter。 */
        internal fun start(
            context: Context,
            config: CrashReporterConfig,
            serviceConfig: CrashServiceConfig,
            networkFactory: CrashNetworkClientFactory,
            runtimeIdentity: RuntimeIdentity,
            anonymousDeviceId: String,
        ): CrashReporter {
            val applicationContext = context.applicationContext
            val queueRoot = File(applicationContext.noBackupFilesDir, QUEUE_DIRECTORY)
            val queue = FileCrashQueue(queueRoot)
            val eventFactory = CrashEventFactory(
                config = config,
                serviceConfig = serviceConfig,
                sessionId = runtimeIdentity.sessionId,
                processId = runtimeIdentity.processId,
                anonymousDeviceId = anonymousDeviceId,
                deviceInfo = CrashDeviceInfo(
                    osVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" },
                    deviceModel = Build.MODEL.orEmpty().ifBlank { "unknown" },
                ),
                networkTypeProvider = AndroidNetworkTypeProvider(applicationContext),
            )
            val uploader = CrashUploader(
                queue = queue,
                sender = CrashBatchSender { request ->
                    networkFactory.crashNetworkClient.sendBatch(request)
                },
                config = serviceConfig,
                logger = { message -> Log.w(TAG, message) },
            )
            val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "crash-upload").apply { isDaemon = true }
            }
            val previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            return try {
                CrashReporter(
                    context = applicationContext,
                    config = config,
                    serviceConfig = serviceConfig,
                    queue = queue,
                    eventFactory = eventFactory,
                    uploader = uploader,
                    scheduler = scheduler,
                    previousExceptionHandler = previousExceptionHandler,
                )
            } catch (throwable: Throwable) {
                scheduler.shutdownNow()
                if (Thread.getDefaultUncaughtExceptionHandler() !== previousExceptionHandler) {
                    Thread.setDefaultUncaughtExceptionHandler(previousExceptionHandler)
                }
                throw throwable
            }
        }

        /** Crash/Jank 共用的持久匿名设备标识，仍沿用 Crash 队列目录以保留已有身份。 */
        internal fun loadAnonymousDeviceId(context: Context): String {
            val applicationContext = context.applicationContext
            val queueRoot = File(applicationContext.noBackupFilesDir, QUEUE_DIRECTORY)
            return DeviceIdentityStore(File(queueRoot, "anonymous-device-id")).getOrCreate()
        }

    }
}
