package com.shanshui.performance.network

/** FPS 指标批次请求；与 Crash 事件使用独立类型，避免 eventType 载荷混用。 */
internal data class FpsBatchRequest(
    val requestId: String,
    val events: List<FpsMetricEvent>,
)

/** 服务端 frame_scene_summary v2 的公共事件信封。 */
internal data class FpsMetricEvent(
    val schemaVersion: Int,
    val eventId: String,
    val eventType: String,
    val occurredAt: Long,
    val sessionId: String,
    /** 当前 Android 进程的稳定身份，服务端要求为 UUID v4。 */
    val processId: String,
    val anonymousDeviceId: String,
    val packageName: String,
    val appVersion: String,
    val versionCode: Int,
    val buildId: String,
    val environment: String,
    val channel: String,
    val osVersion: String,
    val deviceModel: String,
    val networkType: String? = null,
    val frameSceneSummary: FrameSceneSummaryPayload,
)

/** 服务端 frame_scene_summary v2 的专用载荷。 */
internal data class FrameSceneSummaryPayload(
    val scene: String,
    val algorithmVersion: String,
    val activeDurationMs: Long,
    val uiRefreshFrameCount: Long,
    val refreshRateHz: Double,
    val normalizedFps60: Double,
    val frameDurationHistogram: Map<String, Int>? = null,
)

/** FPS 批次响应；字段与服务端批量接收契约保持一致。 */
internal data class FpsBatchResponse(
    val requestId: String? = null,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val duplicate: Int = 0,
    val retryable: Boolean = false,
    val retryAfterSeconds: Long? = null,
    val errors: List<FpsBatchError> = emptyList(),
)

/** FPS 批次中的单事件错误。 */
internal data class FpsBatchError(
    val index: Int? = null,
    val eventId: String? = null,
    val code: String? = null,
    val message: String? = null,
    val retryable: Boolean = false,
)
