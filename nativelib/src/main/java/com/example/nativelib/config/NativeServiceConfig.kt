package com.example.nativelib.config

import com.example.nativelib.FpsLogLevel
import com.example.nativelib.PerformanceConfig
import com.example.nativelib.network.NetworkConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * SDK 初始化后使用的不可变服务配置。
 *
 * 该类型只在 nativelib 内部流转。App Key 仅供网络拦截器使用，不能通过日志或
 * [toString] 暴露。
 */
internal data class NativeServiceConfig(
    val baseUrl: String,
    val appKey: String,
    val environment: String,
    val channel: String,
    val schemaVersion: Int,
    val enableNetworkLogging: Boolean,
    val connectTimeoutMillis: Long,
    val readTimeoutMillis: Long,
    val writeTimeoutMillis: Long,
    val crashEnabled: Boolean,
    val crashBatchSize: Int,
    val crashMaxBatchBytes: Int,
    val crashUploadIntervalMillis: Long,
    val jankEnabled: Boolean,
    val jankUploadIntervalMillis: Long,
    val jankMaxArtifactBytes: Long,
    val fpsEnabled: Boolean = true,
    val fpsLogLevel: FpsLogLevel = FpsLogLevel.OFF,
    val fpsSnapshotIntervalMillis: Long = 5_000L,
    val fpsUploadIntervalMillis: Long = 30_000L,
    val fpsBatchSize: Int = 20,
    val fpsMaxBatchBytes: Int = 512 * 1024,
    val fpsQueueDiskQuotaBytes: Long = 20L * 1024L * 1024L,
    val fpsEventTtlMillis: Long = 7L * 24L * 60L * 60L * 1000L,
) {
    init {
        require(
            baseUrl.toHttpUrlOrNull()?.let { url ->
                url.scheme == "http" || url.scheme == "https"
            } == true,
        ) { "native baseUrl must be a valid http(s) URL" }
        require(appKey.isNotBlank()) { "native appKey must not be blank" }
        require(environment.isNotBlank()) { "native environment must not be blank" }
        require(channel.isNotBlank()) { "native channel must not be blank" }
        require(schemaVersion > 0) { "native schemaVersion must be positive" }
        require(connectTimeoutMillis > 0) { "native connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0) { "native readTimeoutMillis must be positive" }
        require(writeTimeoutMillis > 0) { "native writeTimeoutMillis must be positive" }
        require(crashBatchSize in 1..MAX_CRASH_BATCH_SIZE) {
            "native crashBatchSize must be between 1 and $MAX_CRASH_BATCH_SIZE"
        }
        require(crashMaxBatchBytes in MIN_CRASH_BATCH_BYTES..MAX_CRASH_REQUEST_BYTES) {
            "native crashMaxBatchBytes must be between 16 KiB and 1 MiB"
        }
        require(crashUploadIntervalMillis > 0) {
            "native crashUploadIntervalMillis must be positive"
        }
        require(jankUploadIntervalMillis > 0) {
            "native jankUploadIntervalMillis must be positive"
        }
        require(jankMaxArtifactBytes in 1..MAX_JANK_ARTIFACT_BYTES) {
            "native jankMaxArtifactBytes must be between 1 byte and 64 MiB"
        }
        require(fpsSnapshotIntervalMillis > 0) {
            "native fpsSnapshotIntervalMillis must be positive"
        }
        require(fpsUploadIntervalMillis > 0) {
            "native fpsUploadIntervalMillis must be positive"
        }
        require(fpsBatchSize in 1..MAX_FPS_BATCH_SIZE) {
            "native fpsBatchSize must be between 1 and $MAX_FPS_BATCH_SIZE"
        }
        require(fpsMaxBatchBytes in MIN_FPS_BATCH_BYTES..MAX_BATCH_BYTES) {
            "native fpsMaxBatchBytes must be between 16 KiB and 1 MiB"
        }
        require(fpsQueueDiskQuotaBytes >= fpsMaxBatchBytes) {
            "native fpsQueueDiskQuotaBytes must not be smaller than fpsMaxBatchBytes"
        }
        require(fpsEventTtlMillis > 0) {
            "native fpsEventTtlMillis must be positive"
        }
    }

    override fun toString(): String {
        fun redact(value: String): String = value.replace(appKey, "<redacted>")

        return "NativeServiceConfig(" +
            "baseUrl='${redact(baseUrl)}', " +
            "appKey=<redacted>, " +
            "environment='${redact(environment)}', " +
            "channel='${redact(channel)}', " +
            "schemaVersion=$schemaVersion, " +
            "enableNetworkLogging=$enableNetworkLogging, " +
            "connectTimeoutMillis=$connectTimeoutMillis, " +
            "readTimeoutMillis=$readTimeoutMillis, " +
            "writeTimeoutMillis=$writeTimeoutMillis, " +
            "crashEnabled=$crashEnabled, " +
            "crashBatchSize=$crashBatchSize, " +
            "crashMaxBatchBytes=$crashMaxBatchBytes, " +
            "crashUploadIntervalMillis=$crashUploadIntervalMillis, " +
            "jankEnabled=$jankEnabled, " +
            "jankUploadIntervalMillis=$jankUploadIntervalMillis, " +
            "jankMaxArtifactBytes=$jankMaxArtifactBytes, " +
            "fpsEnabled=$fpsEnabled, " +
            "fpsLogLevel=$fpsLogLevel, " +
            "fpsSnapshotIntervalMillis=$fpsSnapshotIntervalMillis, " +
            "fpsUploadIntervalMillis=$fpsUploadIntervalMillis, " +
            "fpsBatchSize=$fpsBatchSize, " +
            "fpsMaxBatchBytes=$fpsMaxBatchBytes, " +
            "fpsQueueDiskQuotaBytes=$fpsQueueDiskQuotaBytes, " +
            "fpsEventTtlMillis=$fpsEventTtlMillis)"
    }

    companion object {
        const val MAX_CRASH_BATCH_SIZE = 50
        const val MIN_CRASH_BATCH_BYTES = 16 * 1024
        const val MAX_CRASH_REQUEST_BYTES = 1024 * 1024
        const val MAX_JANK_ARTIFACT_BYTES = 64L * 1024L * 1024L
        const val MAX_FPS_BATCH_SIZE = 50
        const val MIN_FPS_BATCH_BYTES = 16 * 1024
        const val MAX_BATCH_BYTES = 1024 * 1024
    }
}

/** 将公共初始化配置和调用方传入的 App Key 固化为内部有效配置。 */
internal fun PerformanceConfig.toNativeServiceConfig(appKey: String): NativeServiceConfig {
    val normalizedAppKey = appKey.trim()
    require(normalizedAppKey.isNotEmpty()) { "appKey must not be blank" }
    return NativeServiceConfig(
        baseUrl = service.baseUrl.trim(),
        appKey = normalizedAppKey,
        environment = service.environment.trim(),
        channel = service.channel.trim(),
        schemaVersion = service.schemaVersion,
        enableNetworkLogging = service.enableNetworkLogging,
        connectTimeoutMillis = service.connectTimeoutMillis,
        readTimeoutMillis = service.readTimeoutMillis,
        writeTimeoutMillis = service.writeTimeoutMillis,
        crashEnabled = crash.enabled,
        crashBatchSize = crash.batchSize,
        crashMaxBatchBytes = crash.maxBatchBytes,
        crashUploadIntervalMillis = crash.uploadIntervalMillis,
        jankEnabled = jank.enabled,
        jankUploadIntervalMillis = jank.uploadIntervalMillis,
        jankMaxArtifactBytes = jank.maxArtifactBytes,
        fpsEnabled = jank.enabled && jank.fps.enabled,
        fpsLogLevel = jank.fps.logLevel,
        fpsSnapshotIntervalMillis = jank.fps.snapshotIntervalMillis,
        fpsUploadIntervalMillis = jank.fps.uploadIntervalMillis,
        fpsBatchSize = jank.fps.batchSize,
        fpsMaxBatchBytes = jank.fps.maxBatchBytes,
        fpsQueueDiskQuotaBytes = jank.fps.queueDiskQuotaBytes,
        fpsEventTtlMillis = jank.fps.eventTtlMillis,
    )
}

/** 将内部公共配置映射到 OkHttp/Retrofit 使用的网络配置。 */
internal fun NativeServiceConfig.toNetworkConfig(): NetworkConfig {
    return NetworkConfig(
        baseUrl = baseUrl,
        appKey = appKey,
        schemaVersion = schemaVersion,
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
        writeTimeoutMillis = writeTimeoutMillis,
        enableLogging = enableNetworkLogging,
    )
}
