package com.example.nativelib

import android.app.Application
import android.app.Activity
import android.util.Log
import com.bytedance.rheatrace.RheaTrace3
import com.example.nativelib.config.toNativeServiceConfig
import com.example.nativelib.crash.CrashReporter
import com.example.nativelib.crash.CrashReporterConfig
import com.example.nativelib.jank.JankArtifactExportCallback
import com.example.nativelib.jank.JankArtifactReporter
import com.example.nativelib.fps.FpsReporter
import com.example.nativelib.memory.MemoryReporter
import com.example.nativelib.memory.leak.MemoryLeakWatcher
import com.example.nativelib.memory.oom.OOMMonitorInitTask
import com.example.nativelib.network.NetworkClientFactory
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 初始化阶段，供调用方区分配置和各 Reporter 失败。 */
enum class PerformanceInitializationStage {
    VALIDATION,
    METADATA,
    NETWORK,
    CRASH,
    JANK,
    FPS,
    MEMORY,
    MEMORY_LEAK,
}

/** SDK 初始化失败；异常消息只包含阶段和非敏感状态，不包含 App Key。 */
class PerformanceInitializationException(
    val stage: PerformanceInitializationStage,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException("[$stage] $message", cause)

/**
 * Crash、线上卡顿、FPS 和内存指标的统一生命周期门面。
 *
 * 应用通常只需在 Application.onCreate 中调用：
 *
 * `PerformanceSdk.initialize(this, appKey)`
 */
class PerformanceSdk private constructor(
    private val componentFactory: PerformanceComponentFactory,
    private val crashReporter: CrashReporter?,
    private val networkFactory: NetworkClientFactory,
    private val jankReporterReady: Boolean,
    private val fpsReporter: FpsReporter?,
    private val memoryReporter: MemoryReporter?,
    private val memoryLeakWatcher: MemoryLeakWatcher?,
    private val koomStartedBySdk: Boolean,
    private val traceStartedBySdk: Boolean,
    private val appKeyFingerprint: String,
    private val config: PerformanceConfig,
    private val jankResult: RheaTrace3.InitResult,
) : Closeable {
    private val closed = AtomicBoolean(false)

    /** Rhea 不支持或被配置关闭时为 false；Crash 状态不受此值影响。 */
    val isJankAvailable: Boolean
        get() = jankReporterReady && !closed.get()

    /** Jank 初始化的最终状态；不支持设备时为 UNSUPPORTED_DEVICE/NOT_MAIN_PROCESS。 */
    val jankInitResult: RheaTrace3.InitResult
        get() = jankResult

    /** FPS API 24+ 且配置可用时为 true；关闭 SDK 后返回 false。 */
    val isFpsAvailable: Boolean
        get() = fpsReporter?.isAvailable == true && !closed.get()

    /** 主进程内存采集可用时为 true；关闭 SDK 后返回 false。 */
    val isMemoryAvailable: Boolean
        get() = memoryReporter?.isAvailable == true && !closed.get()

    /** Activity 泄漏检测和 KOOM 联动可用时为 true；关闭 SDK 后返回 false。 */
    val isMemoryLeakAvailable: Boolean
        get() = memoryLeakWatcher?.isAvailable == true && !closed.get()

    /** 异步触发所有已启用事件队列刷新。 */
    fun flushAsync() {
        if (closed.get()) {
            return
        }
        crashReporter?.flushAsync()
        if (jankReporterReady) {
            JankArtifactReporter.flushAsync()
        }
        fpsReporter?.flushAsync()
        memoryReporter?.flushAsync()
    }

    /** 刷新队列的便捷别名；实际发送仍在后台线程执行。 */
    fun flush() {
        flushAsync()
    }

    /** 返回 Crash 封存队列中的待上传事件数量。 */
    fun pendingCrashEventCount(): Int = crashReporter?.pendingEventCount() ?: 0

    /** 返回 Jank ZIP 封存队列中的待上传产物数量。 */
    fun pendingJankArtifactCount(): Int {
        return if (jankReporterReady) JankArtifactReporter.pendingArtifactCount() else 0
    }

    /** 返回 FPS 封存队列中的待上传事件数量。 */
    fun pendingFpsEventCount(): Int = fpsReporter?.pendingEventCount() ?: 0

    /** 返回内存指标封存队列中的待上传事件数量。 */
    fun pendingMemoryEventCount(): Int = memoryReporter?.pendingEventCount() ?: 0

    /** 为指定 Activity 设置 FPS 场景名；传 null 恢复 Activity 完整类名。 */
    fun setFpsScene(activity: Activity, scene: String?) {
        fpsReporter?.setFpsScene(activity, scene)
    }

    /** 导出卡顿并写入持久上传队列；Jank 不可用时返回 NOT_INITIALIZED。 */
    fun exportAndEnqueue(
        event: RheaTrace3.JankEvent,
        callback: JankArtifactExportCallback,
    ): RheaTrace3.ExportRequestResult {
        if (!isJankAvailable) {
            return RheaTrace3.ExportRequestResult.NOT_INITIALIZED
        }
        return JankArtifactReporter.exportAndEnqueue(event, callback)
    }

    /** 在调用线程完成生命周期解绑，在 reporter 自有后台线程封存队列并释放网络资源。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        // 与 initialize 共用同一把锁，避免关闭资源期间并发启动第二组 Reporter。
        synchronized(instance) {
            instance.compareAndSet(this, null)
            runCatching { memoryLeakWatcher?.close() }
                .onFailure { logCloseFailure("memory leak") }
            if (koomStartedBySdk) {
                runCatching { componentFactory.stopKoom() }
                    .onFailure { logCloseFailure("koom") }
            }
            runCatching { memoryReporter?.close() }
                .onFailure { logCloseFailure("memory") }
            runCatching { fpsReporter?.close() }
                .onFailure { logCloseFailure("fps") }
            runCatching {
                if (jankReporterReady) {
                    componentFactory.closeJank()
                }
            }.onFailure { logCloseFailure("jank") }
            runCatching { crashReporter?.close() }
                .onFailure { logCloseFailure("crash") }
            if (traceStartedBySdk) {
                runCatching { componentFactory.stopRhea() }
                    .onFailure { logCloseFailure("rhea") }
            }
            runCatching { networkFactory.close() }
                .onFailure { logCloseFailure("network") }
        }
    }

    /** 记录资源关闭失败的稳定组件名，不输出底层异常正文。 */
    private fun logCloseFailure(component: String) {
        Log.w(TAG, "unable to close $component reporter")
    }

    companion object {
        private const val TAG = "PerformanceSdk"
        private val instance = AtomicReference<PerformanceSdk?>()

        /** 使用默认配置初始化。 */
        @JvmStatic
        fun initialize(application: Application, appKey: String): PerformanceSdk {
            return initialize(application, appKey, PerformanceConfig())
        }

        /** 返回当前进程已初始化的 SDK；未初始化时返回 null。 */
        @JvmStatic
        fun current(): PerformanceSdk? = instance.get()

        /** 使用统一嵌套配置初始化 Crash 与线上 Jank。 */
        @JvmStatic
        fun initialize(
            application: Application,
            appKey: String,
            config: PerformanceConfig,
        ): PerformanceSdk {
            val normalizedAppKey = appKey.trim()
            require(normalizedAppKey.isNotEmpty()) { "appKey must not be blank" }
            synchronized(instance) {
                instance.get()?.let { existing ->
                    return existing.requireSameInitialization(normalizedAppKey, config)
                }

                // KOOM 的 HeapAnalysisService 运行在 :heap_analysis 独立进程。
                // 该进程也会执行宿主 Application.onCreate，但不会创建主进程的
                // ActivityLeakWatcher；先补齐 CommonConfig，避免分析服务初始化
                // OOMFileManager 时访问未初始化的 MonitorManager.commonConfig。
                if (OOMMonitorInitTask.isHeapAnalysisProcess(application)) {
                    try {
                        OOMMonitorInitTask.ensureCommonConfig(application)
                    } catch (throwable: Throwable) {
                        throw PerformanceInitializationException(
                            PerformanceInitializationStage.MEMORY_LEAK,
                            "unable to initialize KOOM analysis process",
                            throwable,
                        )
                    }
                }
                val componentFactory = PerformanceComponentFactoryProvider.current

                val serviceConfig = runCatching {
                    config.toNativeServiceConfig(normalizedAppKey)
                }.getOrElse { throwable ->
                    throw PerformanceInitializationException(
                        PerformanceInitializationStage.VALIDATION,
                        "invalid SDK configuration",
                        throwable,
                    )
                }
                val metadata = runCatching {
                    ApplicationMetadataResolver.resolve(application)
                }.getOrElse { throwable ->
                    throw PerformanceInitializationException(
                        PerformanceInitializationStage.METADATA,
                        "unable to resolve application metadata",
                        throwable,
                    )
                }
                val networkFactory = runCatching {
                    componentFactory.createNetwork(serviceConfig)
                }.getOrElse { throwable ->
                    throw PerformanceInitializationException(
                        PerformanceInitializationStage.NETWORK,
                        "unable to create network client",
                        throwable,
                    )
                }

                var crashReporter: CrashReporter? = null
                var fpsReporter: FpsReporter? = null
                var memoryReporter: MemoryReporter? = null
                var memoryLeakWatcher: MemoryLeakWatcher? = null
                var koomStartedBySdk = false
                var memoryLeakInitializationAttempted = false
                var jankReporterReady = false
                var traceStartedBySdk = false
                var jankResult = RheaTrace3.InitResult.DISABLED
                // 低版本和子进程不创建 reporter，避免共享队列被多个进程同时恢复或上传。
                val fpsProcessAvailable = runCatching {
                    FpsReporter.isProcessAvailable(application)
                }.getOrDefault(false)
                val memoryProcessAvailable = runCatching {
                    MemoryReporter.isProcessAvailable(application)
                }.getOrDefault(false)
                val memoryLeakProcessAvailable = if (config.memoryLeak.enabled) {
                    runCatching {
                        componentFactory.isMemoryLeakAvailable(application)
                    }.getOrDefault(false)
                } else {
                    false
                }
                try {
                    val anonymousDeviceId = if (
                        serviceConfig.crashEnabled || serviceConfig.jankEnabled ||
                            serviceConfig.memoryEnabled ||
                            (config.memoryLeak.enabled && memoryLeakProcessAvailable)
                    ) {
                        componentFactory.loadAnonymousDeviceId(application)
                    } else {
                        ""
                    }
                    val buildId = config.crash.buildId ?: metadata.defaultBuildId

                    if (serviceConfig.crashEnabled) {
                        val crashConfig = CrashReporterConfig(
                            packageName = metadata.packageName,
                            appVersion = config.crash.appVersion ?: metadata.versionName,
                            versionCode = config.crash.versionCode ?: metadata.versionCode,
                            buildId = buildId,
                            applicationPackage = config.crash.applicationPackage
                                ?: metadata.packageName,
                        )
                        crashReporter = componentFactory.startCrash(
                            application = application,
                            config = crashConfig,
                            serviceConfig = serviceConfig,
                            networkFactory = networkFactory,
                            anonymousDeviceId = anonymousDeviceId,
                        )
                    }

                    if (serviceConfig.jankEnabled) {
                        val onlineTraceConfig = config.jank.toOnlineTraceConfig(
                            buildId = buildId,
                            anonymousDeviceId = anonymousDeviceId,
                            environment = serviceConfig.environment,
                            channel = serviceConfig.channel,
                        )
                        val result = componentFactory.initializeJank(
                            application = application,
                            serviceConfig = serviceConfig,
                            networkFactory = networkFactory,
                            onlineTraceConfig = onlineTraceConfig,
                        )
                        jankResult = result.traceResult
                        // 即使 Reporter 创建失败，也要回收本次刚启动的 Rhea。
                        traceStartedBySdk = result.traceResult == RheaTrace3.InitResult.STARTED
                        when {
                            result.traceResult == RheaTrace3.InitResult.UNSUPPORTED_DEVICE ||
                                result.traceResult == RheaTrace3.InitResult.NOT_MAIN_PROCESS -> {
                                // 这是预期的平台降级：Crash 仍保持运行。
                            }

                            result.traceResult == RheaTrace3.InitResult.STARTED ||
                                result.traceResult == RheaTrace3.InitResult.ALREADY_STARTED -> {
                                if (!result.reporterReady) {
                                    throw PerformanceInitializationException(
                                        PerformanceInitializationStage.JANK,
                                        "jank reporter did not become ready",
                                    )
                                }
                                jankReporterReady = true
                            }

                            else -> {
                                throw PerformanceInitializationException(
                                    PerformanceInitializationStage.JANK,
                                    "Rhea initialization returned ${result.traceResult}",
                                )
                            }
                        }
                    }

                    if (serviceConfig.fpsEnabled && fpsProcessAvailable) {
                        fpsReporter = componentFactory.initializeFps(
                            application = application,
                            serviceConfig = serviceConfig,
                            fpsConfig = config.jank.fps,
                            metadata = metadata,
                            buildId = buildId,
                            anonymousDeviceId = anonymousDeviceId,
                            networkFactory = networkFactory,
                        )
                    }

                    if (serviceConfig.memoryEnabled && memoryProcessAvailable) {
                        memoryReporter = componentFactory.initializeMemory(
                            application = application,
                            serviceConfig = serviceConfig,
                            memoryConfig = config.memory,
                            metadata = metadata,
                            buildId = buildId,
                            anonymousDeviceId = anonymousDeviceId,
                            networkFactory = networkFactory,
                        )
                    }

                    if (config.memoryLeak.enabled && memoryLeakProcessAvailable) {
                        memoryLeakInitializationAttempted = true
                        componentFactory.initializeKoom(
                            application = application,
                            serviceConfig = serviceConfig,
                            metadata = metadata,
                            buildId = buildId,
                            anonymousDeviceId = anonymousDeviceId,
                            networkFactory = networkFactory,
                        )
                        koomStartedBySdk = true
                        memoryLeakWatcher = componentFactory.initializeMemoryLeak(
                            application = application,
                            memoryLeakConfig = config.memoryLeak,
                        )
                    }

                    val sdk = PerformanceSdk(
                        componentFactory = componentFactory,
                        crashReporter = crashReporter,
                        networkFactory = networkFactory,
                        jankReporterReady = jankReporterReady,
                        fpsReporter = fpsReporter,
                        memoryReporter = memoryReporter,
                        memoryLeakWatcher = memoryLeakWatcher,
                        koomStartedBySdk = koomStartedBySdk,
                        traceStartedBySdk = traceStartedBySdk,
                        appKeyFingerprint = fingerprint(normalizedAppKey),
                        config = config,
                        jankResult = jankResult,
                    )
                    instance.set(sdk)
                    return sdk
                } catch (exception: PerformanceInitializationException) {
                    rollback(
                        componentFactory,
                        crashReporter,
                        jankReporterReady,
                        fpsReporter,
                        memoryReporter,
                        memoryLeakWatcher,
                        koomStartedBySdk,
                        traceStartedBySdk,
                        networkFactory,
                    )
                    throw exception
                } catch (throwable: Throwable) {
                    rollback(
                        componentFactory,
                        crashReporter,
                        jankReporterReady,
                        fpsReporter,
                        memoryReporter,
                        memoryLeakWatcher,
                        koomStartedBySdk,
                        traceStartedBySdk,
                        networkFactory,
                    )
                    throw PerformanceInitializationException(
                        when {
                            crashReporter == null && serviceConfig.crashEnabled ->
                                PerformanceInitializationStage.CRASH

                            fpsReporter == null && serviceConfig.fpsEnabled && fpsProcessAvailable ->
                                PerformanceInitializationStage.FPS

                            memoryReporter == null && serviceConfig.memoryEnabled && memoryProcessAvailable ->
                                PerformanceInitializationStage.MEMORY

                            memoryLeakInitializationAttempted ->
                                PerformanceInitializationStage.MEMORY_LEAK

                            serviceConfig.jankEnabled -> PerformanceInitializationStage.JANK
                            else -> PerformanceInitializationStage.NETWORK
                        },
                        "unable to initialize performance reporters",
                        throwable,
                    )
                }
            }
        }

        /** 初始化失败时按已完成阶段逆序释放 Reporter、Rhea 和网络资源。 */
        private fun rollback(
            componentFactory: PerformanceComponentFactory,
            crashReporter: CrashReporter?,
            jankReporterReady: Boolean,
            fpsReporter: FpsReporter?,
            memoryReporter: MemoryReporter?,
            memoryLeakWatcher: MemoryLeakWatcher?,
            koomStartedBySdk: Boolean,
            traceStartedBySdk: Boolean,
            networkFactory: NetworkClientFactory,
        ) {
            memoryLeakWatcher?.let { runCatching { it.close() } }
            if (koomStartedBySdk) {
                runCatching { componentFactory.stopKoom() }
            }
            memoryReporter?.let { runCatching { it.close() } }
            fpsReporter?.let { runCatching { it.close() } }
            if (jankReporterReady) {
                runCatching { componentFactory.closeJank() }
            }
            crashReporter?.let { runCatching { it.close() } }
            if (traceStartedBySdk) {
                runCatching { componentFactory.stopRhea() }
            }
            runCatching { networkFactory.close() }
        }

        /** 校验重复初始化使用完全相同的 App Key 指纹和配置，保证幂等返回。 */
        private fun PerformanceSdk.requireSameInitialization(
            appKey: String,
            requestedConfig: PerformanceConfig,
        ): PerformanceSdk {
            if (appKeyFingerprint != fingerprint(appKey) || config != requestedConfig) {
                throw IllegalStateException(
                    "PerformanceSdk is already initialized with a different appKey or config",
                )
            }
            return this
        }

        /** 计算 App Key 的不可逆指纹，仅用于同进程重复初始化比较。 */
        private fun fingerprint(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
