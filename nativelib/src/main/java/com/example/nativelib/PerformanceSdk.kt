package com.example.nativelib

import android.app.Application
import android.util.Log
import com.bytedance.rheatrace.RheaTrace3
import com.example.nativelib.config.toNativeServiceConfig
import com.example.nativelib.crash.CrashReporter
import com.example.nativelib.crash.CrashReporterConfig
import com.example.nativelib.jank.JankArtifactExportCallback
import com.example.nativelib.jank.JankArtifactReporter
import com.example.nativelib.network.NetworkClientFactory
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 初始化阶段，供调用方区分配置、Crash 和 Jank 失败。 */
enum class PerformanceInitializationStage {
    VALIDATION,
    METADATA,
    NETWORK,
    CRASH,
    JANK,
}

/** SDK 初始化失败；异常消息只包含阶段和非敏感状态，不包含 App Key。 */
class PerformanceInitializationException(
    val stage: PerformanceInitializationStage,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException("[$stage] $message", cause)

/**
 * Crash 与线上卡顿的统一生命周期门面。
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

    /** 异步触发 Crash 与 Jank 队列刷新。 */
    fun flushAsync() {
        if (closed.get()) {
            return
        }
        crashReporter?.flushAsync()
        if (jankReporterReady) {
            JankArtifactReporter.flushAsync()
        }
    }

    /** 刷新队列的便捷别名；实际发送仍在后台线程执行。 */
    fun flush() {
        flushAsync()
    }

    fun pendingCrashEventCount(): Int = crashReporter?.pendingEventCount() ?: 0

    fun pendingJankArtifactCount(): Int {
        return if (jankReporterReady) JankArtifactReporter.pendingArtifactCount() else 0
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        // 与 initialize 共用同一把锁，避免关闭资源期间并发启动第二组 Reporter。
        synchronized(instance) {
            instance.compareAndSet(this, null)
            runCatching {
                if (jankReporterReady) {
                    componentFactory.closeJank()
                }
            }.onFailure { logCloseFailure("jank") }
            runCatching {
                crashReporter?.close()
            }.onFailure { logCloseFailure("crash") }
            if (traceStartedBySdk) {
                runCatching { componentFactory.stopRhea() }
                    .onFailure { logCloseFailure("rhea") }
            }
            runCatching { networkFactory.close() }
                .onFailure { logCloseFailure("network") }
        }
    }

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
                var jankReporterReady = false
                var traceStartedBySdk = false
                var jankResult = RheaTrace3.InitResult.DISABLED
                try {
                    val anonymousDeviceId = if (
                        serviceConfig.crashEnabled || serviceConfig.jankEnabled
                    ) {
                        componentFactory.loadAnonymousDeviceId(application)
                    } else {
                        ""
                    }
                    val buildId = config.crash.buildId ?: metadata.defaultBuildId

                    if (serviceConfig.crashEnabled) {
                        val crashConfig = CrashReporterConfig(
                            appId = config.crash.appId ?: metadata.packageName,
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

                    val sdk = PerformanceSdk(
                        componentFactory = componentFactory,
                        crashReporter = crashReporter,
                        networkFactory = networkFactory,
                        jankReporterReady = jankReporterReady,
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
                        traceStartedBySdk,
                        networkFactory,
                    )
                    throw exception
                } catch (throwable: Throwable) {
                    rollback(
                        componentFactory,
                        crashReporter,
                        jankReporterReady,
                        traceStartedBySdk,
                        networkFactory,
                    )
                    throw PerformanceInitializationException(
                        when {
                            crashReporter == null && serviceConfig.crashEnabled ->
                                PerformanceInitializationStage.CRASH

                            serviceConfig.jankEnabled -> PerformanceInitializationStage.JANK
                            else -> PerformanceInitializationStage.NETWORK
                        },
                        "unable to initialize performance reporters",
                        throwable,
                    )
                }
            }
        }

        private fun rollback(
            componentFactory: PerformanceComponentFactory,
            crashReporter: CrashReporter?,
            jankReporterReady: Boolean,
            traceStartedBySdk: Boolean,
            networkFactory: NetworkClientFactory,
        ) {
            if (jankReporterReady) {
                runCatching { componentFactory.closeJank() }
            }
            crashReporter?.let { runCatching { it.close() } }
            if (traceStartedBySdk) {
                runCatching { componentFactory.stopRhea() }
            }
            runCatching { networkFactory.close() }
        }

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

        private fun fingerprint(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
