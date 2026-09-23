package com.shanshui.performance.memory.leak

import android.app.Application
import com.shanshui.performance.core.PerformanceEventContext
import com.shanshui.performance.memory.oom.OOMMonitorInitTask
import com.shanshui.performance.network.LeakNetworkClientFactory
import com.shanshui.performance.network.TransportSession
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** KOOM 生命周期的可替换边界，便于验证分析进程准备和停止所有权。 */
internal interface LeakKoomBoundary {
    /** 判断当前进程是否为 KOOM 分析子进程。 */
    fun isHeapAnalysisProcess(application: Application): Boolean

    /** 准备 KOOM 公共配置，但不启动监控循环。 */
    fun ensureCommonConfig(application: Application)

    /** 判断当前设备是否满足 KOOM 的 API/ABI 支持范围。 */
    fun isSupported(): Boolean

    /** 注册 report 回调并启动 KOOM 监控循环。 */
    fun init(application: Application, reporter: MemoryLeakReportReporter)

    /** 停止本组件启动的 KOOM 监控循环。 */
    fun stop()

    /** 在 Activity 泄漏确认后触发 KOOM dump。 */
    fun dump()
}

/** 生产环境使用 OOMMonitorInitTask 的真实 KOOM 边界。 */
private object DefaultLeakKoomBoundary : LeakKoomBoundary {
    /** 判断当前进程是否为 KOOM 分析子进程。 */
    override fun isHeapAnalysisProcess(application: Application): Boolean =
        OOMMonitorInitTask.isHeapAnalysisProcess(application)

    /** 准备 KOOM 公共配置，但不启动监控循环。 */
    override fun ensureCommonConfig(application: Application) {
        OOMMonitorInitTask.ensureCommonConfig(application)
    }

    /** 判断当前设备是否满足 KOOM 的 API/ABI 支持范围。 */
    override fun isSupported(): Boolean = OOMMonitorInitTask.isSupported()

    /** 注册 report 回调并启动 KOOM 监控循环。 */
    override fun init(application: Application, reporter: MemoryLeakReportReporter) {
        OOMMonitorInitTask.init(application, reporter)
    }

    /** 停止本组件启动的 KOOM 监控循环。 */
    override fun stop() {
        OOMMonitorInitTask.stop()
    }

    /** 在 Activity 泄漏确认后触发 KOOM dump。 */
    override fun dump() {
        OOMMonitorInitTask.dump()
    }
}

/** Leak 能力的公开装配入口；KOOM 类型只在本模块内部使用。 */
object LeakComponent {
    /** 启动 Leak 能力；分析子进程只完成公共配置准备，不注册主进程检测器。 */
    fun start(
        application: Application,
        config: MemoryLeakConfig = MemoryLeakConfig(),
        context: PerformanceEventContext,
        session: TransportSession,
    ): LeakHandle = startInternal(
        application = application,
        config = config,
        context = context,
        session = session,
        koomBoundary = DefaultLeakKoomBoundary,
    )

    /** 使用可替换 KOOM 边界启动 Leak，供 JVM 测试验证进程准备和资源归属。 */
    internal fun startInternal(
        application: Application,
        config: MemoryLeakConfig,
        context: PerformanceEventContext,
        session: TransportSession,
        koomBoundary: LeakKoomBoundary,
    ): LeakHandle {
        if (koomBoundary.isHeapAnalysisProcess(application)) {
            koomBoundary.ensureCommonConfig(application)
            return LeakHandle(
                watcher = null,
                monitorStarted = false,
                isAvailable = false,
            )
        }
        if (!config.enabled) {
            // Leak 已注册时仍准备 KOOM 公共配置，但不启动循环和 Activity 监听。
            koomBoundary.ensureCommonConfig(application)
            return LeakHandle(watcher = null, monitorStarted = false, isAvailable = false)
        }
        if (!koomBoundary.isSupported() ||
            !ActivityLeakWatcher.isSupported(application)
        ) {
            return LeakHandle(watcher = null, monitorStarted = false, isAvailable = false)
        }
        val serviceConfig = LeakServiceConfig(
            environment = context.environment,
            channel = context.channel,
            baseUrl = session.config.baseUrl,
            appKey = session.config.appKey,
            connectTimeoutMillis = session.config.connectTimeoutMillis,
            readTimeoutMillis = session.config.readTimeoutMillis,
            writeTimeoutMillis = session.config.writeTimeoutMillis,
            enableNetworkLogging = session.config.enableLogging,
        )
        val networkFactory = LeakNetworkClientFactory.create(session)
        val reporter = MemoryLeakReportReporter.create(
            application = application,
            serviceConfig = serviceConfig,
            metadata = context.applicationMetadata,
            buildId = context.buildId,
            runtimeIdentity = context.runtimeIdentity,
            anonymousDeviceId = context.anonymousDeviceId,
            networkFactory = networkFactory,
        )
        return try {
            koomBoundary.init(application, reporter)
            val watcher = ActivityLeakWatcher(
                application = application,
                config = config,
                onLeakDetected = { koomBoundary.dump() },
            )
            LeakHandle(watcher = watcher, monitorStarted = true, isAvailable = watcher.isAvailable)
        } catch (throwable: Throwable) {
            runCatching { koomBoundary.stop() }
            throw throwable
        }
    }
}

/** Leak 运行句柄；停止 Activity 检测和 KOOM 循环，不关闭共享 TransportSession。 */
class LeakHandle internal constructor(
    /** Activity 检测器。 */
    private val watcher: MemoryLeakWatcher?,
    /** 是否由本句柄启动了 KOOM 循环。 */
    private val monitorStarted: Boolean,
    /** 当前进程是否具备 Leak 检测能力。 */
    val isAvailable: Boolean,
    /** 由本句柄拥有的 KOOM 停止动作。 */
    private val stopMonitor: () -> Unit = { OOMMonitorInitTask.stop() },
) : Closeable {
    /** 关闭动作是否已执行，确保重复 close 不会重复停止外部循环。 */
    private val closed = AtomicBoolean(false)

    /** 关闭检测器和本句柄启动的 KOOM 循环；重复调用安全。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        watcher?.close()
        if (monitorStarted) {
            stopMonitor()
        }
    }
}
