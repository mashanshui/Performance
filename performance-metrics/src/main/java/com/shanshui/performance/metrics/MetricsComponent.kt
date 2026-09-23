package com.shanshui.performance.metrics

import android.app.Activity
import android.app.Application
import com.shanshui.performance.core.PerformanceEventContext
import com.shanshui.performance.fps.FpsReporter
import com.shanshui.performance.identity.RuntimeIdentity
import com.shanshui.performance.memory.MemoryReporter
import com.shanshui.performance.network.MetricsNetworkClientFactory
import com.shanshui.performance.network.TransportSession
import java.io.Closeable

/** FPS 和内存指标的公开装配入口；两项能力可以分别启用。 */
object MetricsComponent {
    /** 创建 Metrics 句柄；两项能力均关闭或当前进程不支持时返回 null。 */
    fun start(
        application: Application,
        fpsConfig: FpsConfig = FpsConfig(),
        memoryConfig: MemoryConfig = MemoryConfig(),
        context: PerformanceEventContext,
        session: TransportSession,
    ): MetricsHandle? {
        val serviceConfig = MetricsServiceConfig(
            environment = context.environment,
            channel = context.channel,
            fpsEnabled = fpsConfig.enabled,
            fpsEventTtlMillis = fpsConfig.eventTtlMillis,
            fpsQueueDiskQuotaBytes = fpsConfig.queueDiskQuotaBytes,
            memoryEnabled = memoryConfig.enabled,
            memoryEventTtlMillis = memoryConfig.eventTtlMillis,
            memoryQueueDiskQuotaBytes = memoryConfig.queueDiskQuotaBytes,
            memoryMaxAttempts = memoryConfig.maxAttempts,
        )
        if ((!fpsConfig.enabled || !FpsReporter.isProcessAvailable(application)) &&
            (!memoryConfig.enabled || !MemoryReporter.isProcessAvailable(application))
        ) {
            return null
        }
        val networkFactory = MetricsNetworkClientFactory.create(session)
        val metadata = context.applicationMetadata
        var fpsReporter: FpsReporter? = null
        var memoryReporter: MemoryReporter? = null
        try {
            if (fpsConfig.enabled && FpsReporter.isProcessAvailable(application)) {
                fpsReporter = FpsReporter.create(
                    application = application,
                    serviceConfig = serviceConfig,
                    fpsConfig = fpsConfig,
                    metadata = metadata,
                    buildId = context.buildId,
                    runtimeIdentity = context.runtimeIdentity,
                    anonymousDeviceId = context.anonymousDeviceId,
                    networkFactory = networkFactory,
                )
            }
            if (memoryConfig.enabled && MemoryReporter.isProcessAvailable(application)) {
                memoryReporter = MemoryReporter.create(
                    application = application,
                    config = serviceConfig,
                    memoryConfig = memoryConfig,
                    metadata = metadata,
                    buildId = context.buildId,
                    runtimeIdentity = context.runtimeIdentity,
                    anonymousDeviceId = context.anonymousDeviceId,
                    networkFactory = networkFactory,
                )
            }
            return MetricsHandle(fpsReporter, memoryReporter)
        } catch (throwable: Throwable) {
            memoryReporter?.close()
            fpsReporter?.close()
            throw throwable
        }
    }
}

/** Metrics 运行句柄；只关闭自身 reporter，不关闭共享 TransportSession。 */
class MetricsHandle internal constructor(
    /** FPS reporter，可能因平台条件不可用。 */
    private val fpsReporter: FpsReporter?,
    /** 内存 reporter，可能因平台条件不可用。 */
    private val memoryReporter: MemoryReporter?,
) : Closeable {
    /** FPS 是否已启动并可采集。 */
    val isFpsAvailable: Boolean
        get() = fpsReporter?.isAvailable == true

    /** 内存指标是否已启动。 */
    val isMemoryAvailable: Boolean
        get() = memoryReporter?.isAvailable == true

    /** 当前待上传 FPS 事件数。 */
    fun pendingFpsEventCount(): Int = fpsReporter?.pendingEventCount() ?: 0

    /** 当前待上传内存事件数。 */
    fun pendingMemoryEventCount(): Int = memoryReporter?.pendingEventCount() ?: 0

    /** 设置 Activity 的 FPS 业务场景。 */
    fun setFpsScene(activity: Activity, scene: String?) {
        fpsReporter?.setFpsScene(activity, scene)
    }

    /** 异步刷新 FPS 和内存队列。 */
    fun flushAsync() {
        fpsReporter?.flushAsync()
        memoryReporter?.flushAsync()
    }

    /** 关闭所有指标监听和调度器；重复调用安全。 */
    override fun close() {
        memoryReporter?.close()
        fpsReporter?.close()
    }
}
