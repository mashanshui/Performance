package com.example.nativelib.network

/**
 * 内存泄漏报告 multipart 请求中的 metadata。
 *
 * 字段集合必须与服务端契约保持一致，report 内容不能嵌入此对象。
 */
internal data class MemoryLeakReportMetadata(
    val schemaVersion: Int,
    val eventId: String,
    val occurredAt: Long,
    val packageName: String,
    val appVersion: String,
    val versionCode: Int,
    val anonymousDeviceId: String,
    val processName: String,
    val sessionId: String,
    val buildId: String,
    val environment: String,
    val channel: String,
)

/** 内存泄漏报告上传响应；服务端成功状态只有 accepted 和 duplicate。 */
internal data class MemoryLeakReportUploadResponse(
    val eventId: String? = null,
    val status: String? = null,
    val issueCount: Int? = null,
    val attachmentStatus: String? = null,
)
