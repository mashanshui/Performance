package com.example.nativelib.crash

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import com.example.nativelib.network.NetworkClientFactory
import com.example.nativelib.network.NetworkConfig
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class CrashReporter private constructor(
    private val context: Context,
    private val config: CrashReporterConfig,
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
        scheduler.execute { flushInBackground() }
    }

    fun pendingEventCount(): Int = queue.count()

    override fun close() {
        unregisterNetworkCallback()
        scheduler.shutdownNow()
        if (Thread.getDefaultUncaughtExceptionHandler() === uncaughtExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousExceptionHandler)
        }
        instance.compareAndSet(this, null)
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
            config.uploadIntervalMillis,
            config.uploadIntervalMillis,
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
        private const val QUEUE_DIRECTORY = "performance-crash-reporter"
        private const val CRASH_HANDLER_FLUSH_TIMEOUT_MILLIS = 3_500L
        private val instance = AtomicReference<CrashReporter?>()

        @JvmStatic
        fun initialize(context: Context, config: CrashReporterConfig): CrashReporter {
            instance.get()?.let { return it }
            synchronized(instance) {
                instance.get()?.let { return it }
                val applicationContext = context.applicationContext
                val queueRoot = File(applicationContext.noBackupFilesDir, QUEUE_DIRECTORY)
                val queue = FileCrashQueue(queueRoot)
                val anonymousDeviceId = DeviceIdentityStore(
                    File(queueRoot, "anonymous-device-id"),
                ).getOrCreate()
                val networkFactory = NetworkClientFactory.create(
                    NetworkConfig(
                        baseUrl = config.baseUrl,
                        projectKey = config.projectKey,
                        schemaVersion = config.schemaVersion,
                        connectTimeoutMillis = config.connectTimeoutMillis,
                        readTimeoutMillis = config.readTimeoutMillis,
                        writeTimeoutMillis = config.writeTimeoutMillis,
                        enableLogging = config.enableNetworkLogging,
                    ),
                )
                val eventFactory = CrashEventFactory(
                    config = config,
                    sessionId = UUID.randomUUID().toString(),
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
                    config = config,
                    logger = { message -> Log.w(TAG, message) },
                )
                val reporter = CrashReporter(
                    context = applicationContext,
                    config = config,
                    queue = queue,
                    eventFactory = eventFactory,
                    uploader = uploader,
                    scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                        Thread(runnable, "crash-upload").apply { isDaemon = true }
                    },
                    previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler(),
                )
                instance.set(reporter)
                return reporter
            }
        }
    }
}
