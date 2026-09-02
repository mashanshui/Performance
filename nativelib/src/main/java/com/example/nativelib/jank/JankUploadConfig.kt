package com.example.nativelib.jank

/** nativelib 服务配置映射出的卡顿上传运行参数。 */
internal data class JankUploadConfig(
    val uploadIntervalMillis: Long,
    val connectTimeoutMillis: Long,
    val readTimeoutMillis: Long,
    val writeTimeoutMillis: Long,
    val maxArtifactBytes: Long,
    val enableNetworkLogging: Boolean,
)
