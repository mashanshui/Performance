package com.shanshui.performance.crash

import android.app.Application
import com.shanshui.performance.core.PerformanceEventContext
import com.shanshui.performance.network.CrashNetworkClientFactory
import com.shanshui.performance.network.TransportSession
import java.io.Closeable

/** Crash 能力的公开装配入口，不向消费者暴露队列、DTO 或线程实现。 */
object CrashComponent {
    /** 按共享上下文和传输会话创建 Crash 句柄；禁用时返回 null。 */
    fun start(
        application: Application,
        config: CrashConfig,
        context: PerformanceEventContext,
        session: TransportSession,
        schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    ): CrashHandle? {
        if (!config.enabled) {
            return null
        }
        val metadata = context.applicationMetadata
        val reporterConfig = CrashReporterConfig(
            packageName = config.applicationPackage ?: metadata.packageName,
            appVersion = config.appVersion ?: metadata.versionName,
            versionCode = config.versionCode ?: metadata.versionCode,
            buildId = config.buildId ?: context.buildId,
            applicationPackage = config.applicationPackage ?: metadata.packageName,
        )
        val serviceConfig = CrashServiceConfig(
            schemaVersion = schemaVersion,
            environment = context.environment,
            channel = context.channel,
            crashUploadIntervalMillis = config.uploadIntervalMillis,
            crashBatchSize = config.batchSize,
            crashMaxBatchBytes = config.maxBatchBytes,
            baseUrl = session.config.baseUrl,
            appKey = session.config.appKey,
            connectTimeoutMillis = session.config.connectTimeoutMillis,
            readTimeoutMillis = session.config.readTimeoutMillis,
            writeTimeoutMillis = session.config.writeTimeoutMillis,
            enableNetworkLogging = session.config.enableLogging,
        )
        val networkFactory = CrashNetworkClientFactory.create(session, schemaVersion)
        val reporter = CrashReporter.start(
            context = application,
            config = reporterConfig,
            serviceConfig = serviceConfig,
            networkFactory = networkFactory,
            runtimeIdentity = context.runtimeIdentity,
            anonymousDeviceId = context.anonymousDeviceId,
        )
        return CrashHandle(reporter)
    }

    /** Crash 默认协议版本。 */
    private const val DEFAULT_SCHEMA_VERSION = 2
}

/** Crash 运行句柄；只管理 Crash 自身监听和队列，不关闭宿主传输会话。 */
class CrashHandle internal constructor(
    /** 实际 Crash reporter。 */
    private val reporter: CrashReporter,
) : Closeable {
    /** 返回当前待上传事件数量。 */
    fun pendingEventCount(): Int = reporter.pendingEventCount()

    /** 异步触发一次队列刷新。 */
    fun flushAsync() {
        reporter.flushAsync()
    }

    /** 关闭 Crash 监听和调度器；重复调用安全。 */
    override fun close() {
        reporter.close()
    }
}
