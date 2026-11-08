package com.shanshui.performance.network

/** 内存指标批次请求；服务端沿用统一 /ingest/v1/batches 路由。 */
internal data class MemoryBatchRequest(
    /** 本次请求关联标识，响应必须原样匹配。 */
    val requestId: String,
    /** 本批已封存原事件，不包含正在采集的 current。 */
    val events: List<MemoryMetricEvent>,
)

/** memory_peak_summary v2 事件信封。 */
internal data class MemoryMetricEvent(
    /** 服务端统一事件协议版本。 */
    val schemaVersion: Int,
    /** 首次有效快照固定的 UUID v4 事件身份。 */
    val eventId: String,
    /** 运行峰值事件类型 memory_peak_summary。 */
    val eventType: String,
    /** 原观测时刻；信封取 lastSampledAt，单项取首次最大值时刻。 */
    val occurredAt: Long,
    /** 原主进程共享会话身份，与 processId 相同。 */
    val sessionId: String,
    /** 当前 Android 进程的稳定身份，服务端要求为 UUID v4。 */
    val processId: String,
    /** 原匿名设备标识，不在日志中输出。 */
    val anonymousDeviceId: String,
    /** 原应用包名，服务端须与授权应用一致。 */
    val packageName: String,
    /** 原运行应用版本名。 */
    val appVersion: String,
    /** 原运行应用版本号。 */
    val versionCode: Int,
    /** 原运行构建标识，恢复及补报不替换。 */
    val buildId: String,
    /** 原运行环境，封存后不取新版本配置。 */
    val environment: String,
    /** 原运行渠道。 */
    val channel: String,
    /** 原运行 Android 版本。 */
    val osVersion: String,
    /** 原运行设备型号。 */
    val deviceModel: String,
    /** 首次有效快照的网络类型，未知可省略。 */
    val networkType: String? = null,
    /** 原运行的完整独立峰值和已持久化观测区间。 */
    val memoryPeakSummary: MemoryPeakSummary,
)

/** 一轮已持久化有效观测的三项独立峰值；不表示准确进程退出时间。 */
internal data class MemoryPeakSummary(
    /** 原主进程名称。 */
    val processName: String,
    /** SDK 本轮开始采集的时刻。 */
    val collectionStartedAt: Long,
    /** 最后一次已持久化有效观测的时刻。 */
    val lastSampledAt: Long,
    /** PSS 观测最大值。 */
    val pss: MemoryPeak? = null,
    /** VSS 观测最大值。 */
    val vss: MemoryPeak? = null,
    /** Java 堆已使用观测最大值。 */
    val javaHeap: MemoryPeak? = null,
)

/** 单项最大值及首次观察到该值时的上下文。 */
internal data class MemoryPeak(
    /** 字节数，真实零有效。 */
    val bytes: Long,
    /** 首次达到最大值的 Unix 毫秒时刻。 */
    val occurredAt: Long,
    /** 峰值时前后台。 */
    val foreground: Boolean,
    /** 峰值时 Activity；后台或未知省略。 */
    val scene: String? = null,
)

/** 内存批次响应。 */
internal data class MemoryBatchResponse(
    /** 本次请求关联标识，响应必须原样匹配。 */
    val requestId: String? = null,
    /** 本批新接受的事件数。 */
    val accepted: Int? = 0,
    /** 本批拒绝的事件数，与 errors 数量一致。 */
    val rejected: Int? = 0,
    /** 服务端已持久化的重复事件数。 */
    val duplicate: Int? = 0,
    /** 当前错误是否允许保留原事件重试。 */
    val retryable: Boolean? = false,
    /** 服务端建议的重试等待秒数。 */
    val retryAfterSeconds: Long? = null,
    /** 按索引或事件 ID 定位的拒绝项。 */
    val errors: List<MemoryBatchError>? = emptyList(),
)

/** 内存批次中的单事件错误。 */
internal data class MemoryBatchError(
    /** 原请求 events 中的零基索引。 */
    val index: Int? = null,
    /** 首次有效快照固定的 UUID v4 事件身份。 */
    val eventId: String? = null,
    /** 服务端错误分类，不作为确认成功依据。 */
    val code: String? = null,
    /** 服务端错误描述，不在默认日志输出正文。 */
    val message: String? = null,
    /** 当前错误是否允许保留原事件重试。 */
    val retryable: Boolean = false,
)
