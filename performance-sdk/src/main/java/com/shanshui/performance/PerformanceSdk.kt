package com.shanshui.performance

import android.app.Activity
import android.app.Application
import com.shanshui.performance.core.PerformanceComponent
import com.shanshui.performance.core.PerformanceEventContext
import com.shanshui.performance.core.PerformanceEventContextFactory
import com.shanshui.performance.core.PerformanceRuntime
import com.shanshui.performance.core.PerformanceRuntimeRegistry
import com.shanshui.performance.core.SharedMetadataConfig
import com.shanshui.performance.crash.CrashComponent
import com.shanshui.performance.crash.CrashHandle
import com.shanshui.performance.jank.JankComponent
import com.shanshui.performance.jank.JankEvent
import com.shanshui.performance.jank.JankExportCallback
import com.shanshui.performance.jank.JankExportRequestStatus
import com.shanshui.performance.jank.JankHandle
import com.shanshui.performance.jank.JankInitResult
import com.shanshui.performance.jank.JankInitStatus
import com.shanshui.performance.memory.leak.LeakHandle
import com.shanshui.performance.memory.leak.LeakComponent
import com.shanshui.performance.metrics.MetricsComponent
import com.shanshui.performance.metrics.MetricsHandle
import com.shanshui.performance.network.NetworkConfig
import com.shanshui.performance.network.TransportSession
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 初始化阶段，供调用方区分配置、网络和功能组件失败。 */
enum class PerformanceInitializationStage {
    /** 配置或 App Key 校验。 */
    VALIDATION,
    /** 应用元数据和进程身份。 */
    METADATA,
    /** 共享 HTTP 会话。 */
    NETWORK,
    /** Crash 组件。 */
    CRASH,
    /** Jank 组件。 */
    JANK,
    /** Metrics 组件。 */
    METRICS,
    /** Leak 组件。 */
    MEMORY_LEAK,
}

/** SDK 初始化失败；消息不包含 App Key。 */
class PerformanceInitializationException(
    /** 失败阶段。 */
    val stage: PerformanceInitializationStage,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException("[$stage] $message", cause)

/** SDK 聚合层的可替换依赖边界，避免集成测试加载真实采集引擎。 */
internal interface PerformanceSdkDependencies {
    /** 创建共享传输会话。 */
    fun createSession(config: NetworkConfig): TransportSession

    /** 创建共享事件上下文。 */
    fun createContext(
        application: Application,
        metadataConfig: SharedMetadataConfig,
    ): PerformanceEventContext

    /** 创建 Crash 组件。 */
    fun createCrash(
        application: Application,
        config: CrashConfig,
        context: PerformanceEventContext,
        session: TransportSession,
        schemaVersion: Int,
    ): CrashHandle?

    /** 创建 Jank 组件。 */
    fun createJank(
        application: Application,
        config: JankConfig,
        context: PerformanceEventContext,
        session: TransportSession,
    ): JankHandle?

    /** 创建 Metrics 组件。 */
    fun createMetrics(
        application: Application,
        fpsConfig: FpsConfig,
        memoryConfig: MemoryConfig,
        context: PerformanceEventContext,
        session: TransportSession,
    ): MetricsHandle?

    /** 创建 Leak 组件。 */
    fun createLeak(
        application: Application,
        config: MemoryLeakConfig,
        context: PerformanceEventContext,
        session: TransportSession,
    ): LeakHandle?
}

/** 生产环境使用真实功能模块的 SDK 依赖实现。 */
private object DefaultPerformanceSdkDependencies : PerformanceSdkDependencies {
    /** 创建共享传输会话。 */
    override fun createSession(config: NetworkConfig): TransportSession =
        TransportSession.create(config)

    /** 创建共享事件上下文。 */
    override fun createContext(
        application: Application,
        metadataConfig: SharedMetadataConfig,
    ): PerformanceEventContext =
        PerformanceEventContextFactory.create(application, metadataConfig)

    /** 创建 Crash 组件。 */
    override fun createCrash(
        application: Application,
        config: CrashConfig,
        context: PerformanceEventContext,
        session: TransportSession,
        schemaVersion: Int,
    ): CrashHandle? = CrashComponent.start(application, config, context, session, schemaVersion)

    /** 创建 Jank 组件。 */
    override fun createJank(
        application: Application,
        config: JankConfig,
        context: PerformanceEventContext,
        session: TransportSession,
    ): JankHandle? = JankComponent.start(application, config, context, session)

    /** 创建 Metrics 组件。 */
    override fun createMetrics(
        application: Application,
        fpsConfig: FpsConfig,
        memoryConfig: MemoryConfig,
        context: PerformanceEventContext,
        session: TransportSession,
    ): MetricsHandle? = MetricsComponent.start(application, fpsConfig, memoryConfig, context, session)

    /** 创建 Leak 组件。 */
    override fun createLeak(
        application: Application,
        config: MemoryLeakConfig,
        context: PerformanceEventContext,
        session: TransportSession,
    ): LeakHandle? = LeakComponent.start(application, config, context, session)
}

/** Crash、Jank、Metrics 和 Leak 的全量生命周期门面。 */
class PerformanceSdk private constructor(
    /** 统一组件运行时。 */
    private val runtime: PerformanceRuntime,
    /** 本次共享传输会话。 */
    private val session: TransportSession,
    /** 本次共享事件上下文。 */
    private val context: PerformanceEventContext,
    /** Crash 组件持有者。 */
    private val crashComponent: FeatureComponent<CrashHandle>,
    /** Jank 组件持有者。 */
    private val jankComponent: FeatureComponent<JankHandle>,
    /** Metrics 组件持有者。 */
    private val metricsComponent: FeatureComponent<MetricsHandle>,
    /** Leak 组件持有者。 */
    private val leakComponent: FeatureComponent<LeakHandle>,
    /** App Key 指纹，只用于重复初始化比较。 */
    private val appKeyFingerprint: String,
    /** 已生效的组合配置。 */
    private val config: PerformanceConfig,
) : Closeable {
    /** 防止重复关闭。 */
    private val closed = AtomicBoolean(false)

    /** Jank 是否已经可用。 */
    val isJankAvailable: Boolean
        get() = !closed.get() && jankComponent.value?.isAvailable == true

    /** Jank 初始化状态；未启用时返回 DISABLED。 */
    val jankInitResult: JankInitResult
        get() = jankComponent.value?.initResult
            ?: JankInitResult(JankInitStatus.DISABLED, reporterReady = false)

    /** FPS 是否可用。 */
    val isFpsAvailable: Boolean
        get() = !closed.get() && metricsComponent.value?.isFpsAvailable == true

    /** 内存指标是否可用。 */
    val isMemoryAvailable: Boolean
        get() = !closed.get() && metricsComponent.value?.isMemoryAvailable == true

    /** Activity 泄漏检测是否可用。 */
    val isMemoryLeakAvailable: Boolean
        get() = !closed.get() && leakComponent.value?.isAvailable == true

    /** 当前进程共享的启动实例标识。 */
    val sessionId: String
        get() = context.runtimeIdentity.sessionId

    /** 当前 Android 进程标识。 */
    val processId: String
        get() = context.runtimeIdentity.processId

    /** 设置 Activity 的 FPS 场景名。 */
    fun setFpsScene(activity: Activity, scene: String?) {
        if (!closed.get()) {
            metricsComponent.value?.setFpsScene(activity, scene)
        }
    }

    /** 导出 SDK 自有 Jank 事件并加入持久队列。 */
    fun exportAndEnqueue(event: JankEvent, callback: JankExportCallback): JankExportRequestStatus {
        if (closed.get()) {
            return JankExportRequestStatus.NOT_INITIALIZED
        }
        return jankComponent.value?.exportAndEnqueue(event, callback)
            ?: JankExportRequestStatus.NOT_INITIALIZED
    }

    /** 异步刷新所有已启动功能的上传队列。 */
    fun flushAsync() {
        if (closed.get()) return
        crashComponent.value?.flushAsync()
        jankComponent.value?.flushAsync()
        metricsComponent.value?.flushAsync()
    }

    /** 关闭全部组件和共享传输会话；重复调用安全。 */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            PerformanceRuntimeRegistry.close()
            instance.compareAndSet(this, null)
        }
    }

    companion object {
        /** 进程内全量 SDK 单例。 */
        private val instance = AtomicReference<PerformanceSdk?>()

        /** 使用默认配置初始化全量 SDK。 */
        fun initialize(application: Application, appKey: String): PerformanceSdk {
            return initialize(application, appKey, PerformanceConfig())
        }

        /** 返回当前进程已经初始化的 SDK。 */
        fun current(): PerformanceSdk? = instance.get()

        /** 校验配置、构造共享上下文并按显式组件列表启动 SDK。 */
        fun initialize(
            application: Application,
            appKey: String,
            config: PerformanceConfig,
        ): PerformanceSdk {
            return initializeInternal(
                application = application,
                appKey = appKey,
                config = config,
                dependencies = DefaultPerformanceSdkDependencies,
            )
        }

        /** 使用替换依赖构造 SDK，供聚合层 JVM 集成测试验证完整生命周期。 */
        internal fun initializeForTesting(
            application: Application,
            appKey: String,
            config: PerformanceConfig,
            dependencies: PerformanceSdkDependencies,
        ): PerformanceSdk {
            return initializeInternal(application, appKey, config, dependencies)
        }

        /** 校验配置并通过指定依赖完成一次全量组件装配。 */
        private fun initializeInternal(
            application: Application,
            appKey: String,
            config: PerformanceConfig,
            dependencies: PerformanceSdkDependencies,
        ): PerformanceSdk {
            val normalizedAppKey = appKey.trim()
            require(normalizedAppKey.isNotEmpty()) { "appKey must not be blank" }
            synchronized(instance) {
                instance.get()?.let { existing ->
                    if (existing.appKeyFingerprint == fingerprint(normalizedAppKey) &&
                        existing.config == config
                    ) {
                        return existing
                    }
                    throw IllegalStateException(
                        "PerformanceSdk is already initialized with a different appKey or config",
                    )
                }
                val session = try {
                    dependencies.createSession(config.service.toNetworkConfig(normalizedAppKey))
                } catch (throwable: Throwable) {
                    throw PerformanceInitializationException(
                        PerformanceInitializationStage.NETWORK,
                        "unable to create transport session",
                        throwable,
                    )
                }
                val eventContext = try {
                    dependencies.createContext(
                        application = application,
                        metadataConfig = SharedMetadataConfig(
                            environment = config.service.environment,
                            channel = config.service.channel,
                            buildId = config.service.buildId,
                        ),
                    )
                } catch (throwable: Throwable) {
                    session.close()
                    throw PerformanceInitializationException(
                        PerformanceInitializationStage.METADATA,
                        "unable to resolve application metadata",
                        throwable,
                    )
                }
                val crash = FeatureComponent("crash") {
                    dependencies.createCrash(
                        application,
                        config.crash,
                        eventContext,
                        session,
                        config.service.schemaVersion,
                    )
                }
                val jank = FeatureComponent("jank") {
                    dependencies.createJank(application, config.jank, eventContext, session)
                }
                val metrics = FeatureComponent("metrics") {
                    dependencies.createMetrics(
                        application,
                        config.fps,
                        config.memory,
                        eventContext,
                        session,
                    )
                }
                val leak = FeatureComponent("leak", prepareOnPrepare = true) {
                    dependencies.createLeak(application, config.memoryLeak, eventContext, session)
                }
                val transport = object : PerformanceComponent {
                    override val id: String = "transport"
                    override fun prepare() = Unit
                    override fun start() = Unit
                    override fun close() = session.close()
                }
                val runtime = PerformanceRuntimeRegistry.initialize(
                    components = listOf(transport, leak, crash, jank, metrics),
                    descriptor = descriptor(normalizedAppKey, config),
                )
                val sdk = PerformanceSdk(
                    runtime = runtime,
                    session = session,
                    context = eventContext,
                    crashComponent = crash,
                    jankComponent = jank,
                    metricsComponent = metrics,
                    leakComponent = leak,
                    appKeyFingerprint = fingerprint(normalizedAppKey),
                    config = config,
                )
                instance.set(sdk)
                return sdk
            }
        }

        /** 生成不包含明文 App Key 的稳定配置描述。 */
        private fun descriptor(appKey: String, config: PerformanceConfig): String {
            return fingerprint(appKey) + ":" + config.toString()
        }

        /** 计算 App Key SHA-256 指纹。 */
        private fun fingerprint(value: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

/** 把功能启动延迟到运行时阶段的内部组件包装器。 */
private class FeatureComponent<T : Closeable>(
    /** 稳定功能标识。 */
    override val id: String,
    /** Leak 需要在 prepare 阶段完成分析进程准备。 */
    private val prepareOnPrepare: Boolean = false,
    /** 创建功能句柄。 */
    private val create: () -> T?,
) : PerformanceComponent {
    /** 已创建的功能句柄。 */
    var value: T? = null
        private set

    /** 在需要时提前执行 Leak 的进程准备。 */
    override fun prepare() {
        if (prepareOnPrepare && value == null) {
            value = create()
        }
    }

    /** 启动普通功能；已准备的 Leak 不重复创建。 */
    override fun start() {
        if (value == null) {
            value = create()
        }
    }

    /** 关闭句柄并清空引用；句柄内部保证幂等。 */
    override fun close() {
        value?.close()
        value = null
    }
}
