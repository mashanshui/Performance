package com.example.nativelib.network

/** 内存指标批次请求；服务端沿用统一 /ingest/v1/batches 路由。 */
internal data class MemoryBatchRequest(
    val requestId: String,
    val events: List<MemoryMetricEvent>,
)

/** memory_sample v2 事件信封。 */
internal data class MemoryMetricEvent(
    val schemaVersion: Int,
    val eventId: String,
    val eventType: String,
    val occurredAt: Long,
    val sessionId: String,
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
    val memorySample: MemorySamplePayload,
)

/** 服务端 memorySample 字段；三个指标允许独立缺失。 */
internal data class MemorySamplePayload(
    val pssBytes: Long? = null,
    val vssBytes: Long? = null,
    val javaHeapUsedBytes: Long? = null,
    val processName: String,
    val foreground: Boolean,
    val scene: String? = null,
)

/** 内存批次响应。 */
internal data class MemoryBatchResponse(
    val requestId: String? = null,
    val accepted: Int? = 0,
    val rejected: Int? = 0,
    val duplicate: Int? = 0,
    val retryable: Boolean? = false,
    val retryAfterSeconds: Long? = null,
    val errors: List<MemoryBatchError>? = emptyList(),
)

/** 内存批次中的单事件错误。 */
internal data class MemoryBatchError(
    val index: Int? = null,
    val eventId: String? = null,
    val code: String? = null,
    val message: String? = null,
    val retryable: Boolean = false,
)
