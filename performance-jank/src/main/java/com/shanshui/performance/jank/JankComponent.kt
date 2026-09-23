package com.shanshui.performance.jank

import android.app.Application
import com.bytedance.rheatrace.RheaTrace3
import com.shanshui.performance.core.PerformanceEventContext
import com.shanshui.performance.network.JankNetworkClientFactory
import com.shanshui.performance.network.TransportSession
import java.io.Closeable

/** Jank 初始化状态，不暴露 Rhea 的枚举类型。 */
enum class JankInitStatus {
    /** 本次进程启动并成功初始化。 */
    STARTED,
    /** 采集引擎已由其他调用方启动。 */
    ALREADY_STARTED,
    /** 配置关闭。 */
    DISABLED,
    /** 当前设备不支持。 */
    UNSUPPORTED_DEVICE,
    /** 当前进程不是主进程。 */
    NOT_MAIN_PROCESS,
    /** 初始化失败。 */
    FAILED,
}

/** SDK 自有的 Jank 初始化结果。 */
data class JankInitResult(
    /** 映射后的初始化状态。 */
    val status: JankInitStatus,
    /** 队列上传器是否已准备完成。 */
    val reporterReady: Boolean,
    /** 可供诊断的非敏感错误码。 */
    val errorCode: String? = null,
)

/** SDK 自有的卡顿事件元数据，字段与现有服务端协议保持一致。 */
data class JankEvent(
    /** 事件唯一标识。 */
    val eventId: String,
    /** 事件发生时间。 */
    val occurredAt: Long,
    /** 当前运行 session。 */
    val sessionId: String,
    /** 业务场景。 */
    val scene: String,
    /** 消息开始时间纳秒。 */
    val messageStartNs: Long,
    /** 消息结束时间纳秒。 */
    val messageEndNs: Long,
    /** 卡顿阈值纳秒。 */
    val thresholdNs: Long,
    /** 本次尝试的采样数量。 */
    val attemptedSampleCount: Long,
)

/** SDK 自有的导出请求状态。 */
enum class JankExportRequestStatus {
    /** 请求已接受。 */
    ACCEPTED,
    /** 尚未初始化。 */
    NOT_INITIALIZED,
    /** 事件元数据无效。 */
    INVALID_EVENT,
    /** 存储不可用。 */
    STORAGE_UNAVAILABLE,
    /** 采集引擎返回其他状态。 */
    OTHER,
}

/** SDK 自有的导出完成状态。 */
enum class JankExportStatus {
    /** 完整导出。 */
    SUCCESS,
    /** 部分导出。 */
    PARTIAL,
    /** 导出失败。 */
    FAILED,
    /** 其他引擎状态。 */
    OTHER,
}

/** Jank 导出完成结果，不包含 Rhea 对象。 */
data class JankExportResult(
    /** SDK 自有导出状态。 */
    val status: JankExportStatus,
    /** 导出 ZIP 路径；失败时为空。 */
    val artifactPath: String?,
    /** ZIP 是否已写入持久上传队列。 */
    val queued: Boolean,
)

/** Jank 导出回调。 */
fun interface JankExportCallback {
    /** 在采集引擎回调线程收到导出结果。 */
    fun onCompleted(result: JankExportResult)
}

/** Jank 能力的公开装配和操作入口。 */
object JankComponent {
    /** 按 SDK 自有配置启动 Jank；不支持或关闭时返回对应状态。 */
    fun start(
        application: Application,
        config: JankConfig,
        context: PerformanceEventContext,
        session: TransportSession,
    ): JankHandle {
        val serviceConfig = JankServiceConfig(
            jankEnabled = config.enabled,
            jankUploadIntervalMillis = config.uploadIntervalMillis,
            jankMaxArtifactBytes = config.maxArtifactBytes,
            baseUrl = session.config.baseUrl,
            appKey = session.config.appKey,
            environment = context.environment,
            channel = context.channel,
            connectTimeoutMillis = session.config.connectTimeoutMillis,
            readTimeoutMillis = session.config.readTimeoutMillis,
            writeTimeoutMillis = session.config.writeTimeoutMillis,
            enableNetworkLogging = session.config.enableLogging,
        )
        if (!config.enabled) {
            return JankHandle(JankInitResult(JankInitStatus.DISABLED, reporterReady = false))
        }
        val networkFactory = JankNetworkClientFactory.create(session)
        val onlineConfig = config.toOnlineTraceConfig(
            buildId = context.buildId,
            anonymousDeviceId = context.anonymousDeviceId,
            processId = context.runtimeIdentity.processId,
            environment = context.environment,
            channel = context.channel,
        )
        val result = JankArtifactReporter.initialize(
            application = application,
            serviceConfig = serviceConfig,
            networkFactory = networkFactory,
            onlineTraceConfig = onlineConfig,
        )
        return JankHandle(mapInitResult(result))
    }

    /** 将 Rhea 初始化状态映射为 SDK 自有枚举。 */
    internal fun mapInitResult(result: JankArtifactInitResult): JankInitResult {
        val status = when (result.traceResult) {
            RheaTrace3.InitResult.STARTED -> JankInitStatus.STARTED
            RheaTrace3.InitResult.ALREADY_STARTED -> JankInitStatus.ALREADY_STARTED
            RheaTrace3.InitResult.DISABLED -> JankInitStatus.DISABLED
            RheaTrace3.InitResult.UNSUPPORTED_DEVICE -> JankInitStatus.UNSUPPORTED_DEVICE
            RheaTrace3.InitResult.NOT_MAIN_PROCESS -> JankInitStatus.NOT_MAIN_PROCESS
            else -> JankInitStatus.FAILED
        }
        return JankInitResult(status, result.reporterReady, result.errorCode)
    }
}

/** Jank 运行句柄；关闭队列和监听，不关闭共享 TransportSession。 */
class JankHandle internal constructor(
    /** 初始化结果。 */
    val initResult: JankInitResult,
) : Closeable {
    /** 当前初始化是否可用于导出。 */
    val isAvailable: Boolean
        get() = initResult.reporterReady

    /** 导出卡顿事件并在成功后入队。 */
    fun exportAndEnqueue(event: JankEvent, callback: JankExportCallback): JankExportRequestStatus {
        if (!isAvailable) {
            return JankExportRequestStatus.NOT_INITIALIZED
        }
        val rheaEvent = RheaTrace3.JankEvent.builder()
            .setEventId(event.eventId)
            .setOccurredAt(event.occurredAt)
            .setSessionId(event.sessionId)
            .setScene(event.scene)
            .setMessageStartNs(event.messageStartNs)
            .setMessageEndNs(event.messageEndNs)
            .setThresholdNs(event.thresholdNs)
            .setAttemptedSampleCount(event.attemptedSampleCount)
            .build()
        val result = JankArtifactReporter.exportAndEnqueue(
            rheaEvent,
            JankArtifactExportCallback { exported ->
                callback.onCompleted(
                    JankExportResult(
                        status = when (exported.exportResult.status) {
                            RheaTrace3.ExportStatus.SUCCESS -> JankExportStatus.SUCCESS
                            RheaTrace3.ExportStatus.PARTIAL -> JankExportStatus.PARTIAL
                            else -> JankExportStatus.FAILED
                        },
                        artifactPath = exported.exportResult.artifact.path,
                        queued = exported.queued,
                    ),
                )
            },
        )
        return when (result) {
            RheaTrace3.ExportRequestResult.ACCEPTED -> JankExportRequestStatus.ACCEPTED
            RheaTrace3.ExportRequestResult.NOT_INITIALIZED -> JankExportRequestStatus.NOT_INITIALIZED
            RheaTrace3.ExportRequestResult.INVALID_JANK_METADATA -> JankExportRequestStatus.INVALID_EVENT
            RheaTrace3.ExportRequestResult.STORAGE_UNAVAILABLE -> JankExportRequestStatus.STORAGE_UNAVAILABLE
            else -> JankExportRequestStatus.OTHER
        }
    }

    /** 返回当前待上传 ZIP 数量。 */
    fun pendingArtifactCount(): Int = JankArtifactReporter.pendingArtifactCount()

    /** 触发异步队列刷新。 */
    fun flushAsync(): Boolean = JankArtifactReporter.flushAsync()

    /** 关闭 Jank 队列和监听；重复调用安全。 */
    override fun close() {
        JankArtifactReporter.close()
    }
}
