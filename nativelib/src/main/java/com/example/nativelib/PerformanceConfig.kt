package com.example.nativelib

import com.bytedance.rheatrace.RheaTrace3
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * nativelib 的统一初始化配置。
 *
 * App Key 不放在配置对象中，而是作为 [PerformanceSdk.initialize] 的独立参数传入，
 * 避免配置对象被日志或调试代码打印时意外携带凭据。
 */
data class PerformanceConfig(
    val service: ServiceConfig = ServiceConfig(),
    val crash: CrashConfig = CrashConfig(),
    val jank: JankConfig = JankConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    val memoryLeak: MemoryLeakConfig = MemoryLeakConfig(),
) {
    init {
        requireConfig(service.environment.isNotBlank(), "environment must not be blank")
        requireConfig(service.channel.isNotBlank(), "channel must not be blank")
    }
}

/** Android 进程内存采集和批量上报配置。 */
data class MemoryConfig(
    val enabled: Boolean = true,
    val foregroundSamplingIntervalMillis: Long = DEFAULT_FOREGROUND_SAMPLING_INTERVAL_MILLIS,
    val backgroundSamplingIntervalMillis: Long = DEFAULT_BACKGROUND_SAMPLING_INTERVAL_MILLIS,
    val uploadIntervalMillis: Long = DEFAULT_UPLOAD_INTERVAL_MILLIS,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val maxBatchBytes: Int = DEFAULT_MAX_BATCH_BYTES,
    val queueDiskQuotaBytes: Long = DEFAULT_QUEUE_DISK_QUOTA_BYTES,
    val eventTtlMillis: Long = DEFAULT_EVENT_TTL_MILLIS,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        requireConfig(foregroundSamplingIntervalMillis > 0) {
            "memory foregroundSamplingIntervalMillis must be positive"
        }
        requireConfig(backgroundSamplingIntervalMillis > 0) {
            "memory backgroundSamplingIntervalMillis must be positive"
        }
        requireConfig(uploadIntervalMillis > 0) {
            "memory uploadIntervalMillis must be positive"
        }
        requireConfig(batchSize in 1..MAX_BATCH_SIZE) {
            "memory batchSize must be between 1 and $MAX_BATCH_SIZE"
        }
        requireConfig(maxBatchBytes in MIN_BATCH_BYTES..MAX_BATCH_BYTES) {
            "memory maxBatchBytes must be between 16 KiB and 1 MiB"
        }
        requireConfig(queueDiskQuotaBytes >= maxBatchBytes) {
            "memory queueDiskQuotaBytes must not be smaller than maxBatchBytes"
        }
        requireConfig(eventTtlMillis > 0) { "memory eventTtlMillis must be positive" }
        requireConfig(maxAttempts in 1..MAX_ATTEMPTS) {
            "memory maxAttempts must be between 1 and $MAX_ATTEMPTS"
        }
    }

    companion object {
        const val DEFAULT_FOREGROUND_SAMPLING_INTERVAL_MILLIS = 60_000L
        const val DEFAULT_BACKGROUND_SAMPLING_INTERVAL_MILLIS = 5L * 60L * 1_000L
        const val DEFAULT_UPLOAD_INTERVAL_MILLIS = 30_000L
        const val DEFAULT_BATCH_SIZE = 20
        const val DEFAULT_MAX_BATCH_BYTES = 512 * 1024
        const val DEFAULT_QUEUE_DISK_QUOTA_BYTES = 20L * 1024L * 1024L
        const val DEFAULT_EVENT_TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val DEFAULT_MAX_ATTEMPTS = 10
        const val MAX_BATCH_SIZE = 50
        const val MIN_BATCH_BYTES = 16 * 1024
        const val MAX_BATCH_BYTES = 1024 * 1024
        const val MAX_ATTEMPTS = 100
    }
}

/** Android Activity 泄漏检测与 KOOM dump 联动配置；与内存指标采集完全独立。 */
data class MemoryLeakConfig(
    val enabled: Boolean = true,
    val foregroundScanIntervalMillis: Long = DEFAULT_FOREGROUND_SCAN_INTERVAL_MILLIS,
    val backgroundScanIntervalMillis: Long = DEFAULT_BACKGROUND_SCAN_INTERVAL_MILLIS,
    val maxRecheckCount: Int = DEFAULT_MAX_RECHECK_COUNT,
    val gcDelayMillis: Long = DEFAULT_GC_DELAY_MILLIS,
    val skipWhenDebuggerConnected: Boolean = DEFAULT_SKIP_WHEN_DEBUGGER_CONNECTED,
) {
    init {
        requireConfig(
            foregroundScanIntervalMillis in 1L..MAX_SCAN_INTERVAL_MILLIS,
        ) {
            "memoryLeak foregroundScanIntervalMillis must be between 1ms and 7 days"
        }
        requireConfig(
            backgroundScanIntervalMillis in 1L..MAX_SCAN_INTERVAL_MILLIS,
        ) {
            "memoryLeak backgroundScanIntervalMillis must be between 1ms and 7 days"
        }
        requireConfig(maxRecheckCount in 1..MAX_RECHECK_COUNT) {
            "memoryLeak maxRecheckCount must be between 1 and $MAX_RECHECK_COUNT"
        }
        requireConfig(gcDelayMillis in 1L..MAX_GC_DELAY_MILLIS) {
            "memoryLeak gcDelayMillis must be between 1ms and 5 minutes"
        }
    }

    companion object {
        const val DEFAULT_FOREGROUND_SCAN_INTERVAL_MILLIS = 60_000L
        const val DEFAULT_BACKGROUND_SCAN_INTERVAL_MILLIS = 20L * 60L * 1_000L
        const val DEFAULT_MAX_RECHECK_COUNT = 10
        const val DEFAULT_GC_DELAY_MILLIS = 2_000L
        const val DEFAULT_SKIP_WHEN_DEBUGGER_CONNECTED = true
        const val MAX_SCAN_INTERVAL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val MAX_RECHECK_COUNT = 100
        const val MAX_GC_DELAY_MILLIS = 5L * 60L * 1_000L
    }
}

/** 服务端和网络层的公共配置。 */
data class ServiceConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val environment: String = DEFAULT_ENVIRONMENT,
    val channel: String = DEFAULT_CHANNEL,
    val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    val enableNetworkLogging: Boolean = false,
    val connectTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val readTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val writeTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    init {
        val parsedBaseUrl = baseUrl.trim().toHttpUrlOrNull()
            ?: throw PerformanceInitializationException(
                PerformanceInitializationStage.VALIDATION,
                "baseUrl must be a valid URL",
            )
        requireConfig(parsedBaseUrl.scheme == "http" || parsedBaseUrl.scheme == "https") {
            "baseUrl must use http or https"
        }
        requireConfig(parsedBaseUrl.query == null && parsedBaseUrl.fragment == null) {
            "baseUrl must not contain a query or fragment"
        }
        requireConfig(environment.isNotBlank() && environment.length <= MAX_ENVIRONMENT_LENGTH) {
            "environment must be non-blank and at most $MAX_ENVIRONMENT_LENGTH characters"
        }
        requireConfig(channel.isNotBlank() && channel.length <= MAX_CHANNEL_LENGTH) {
            "channel must be non-blank and at most $MAX_CHANNEL_LENGTH characters"
        }
        requireConfig(schemaVersion > 0, "schemaVersion must be positive")
        requireConfig(connectTimeoutMillis > 0, "connectTimeoutMillis must be positive")
        requireConfig(readTimeoutMillis > 0, "readTimeoutMillis must be positive")
        requireConfig(writeTimeoutMillis > 0, "writeTimeoutMillis must be positive")
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://192.168.0.150:8080"
        const val DEFAULT_ENVIRONMENT = "debug"
        const val DEFAULT_CHANNEL = "official"
        const val DEFAULT_SCHEMA_VERSION = 2
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L
        const val MAX_ENVIRONMENT_LENGTH = 64
        const val MAX_CHANNEL_LENGTH = 128
    }
}

/** Crash 上报配置；应用元数据为空时由 SDK 从 Application 自动解析。 */
data class CrashConfig(
    val enabled: Boolean = true,
    val appVersion: String? = null,
    val versionCode: Int? = null,
    val buildId: String? = null,
    val applicationPackage: String? = null,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val maxBatchBytes: Int = DEFAULT_MAX_BATCH_BYTES,
    val uploadIntervalMillis: Long = DEFAULT_UPLOAD_INTERVAL_MILLIS,
) {
    init {
        requireOptionalText(appVersion, "appVersion")
        requireConfig(versionCode == null || versionCode >= 0) {
            "versionCode must be non-negative"
        }
        requireOptionalText(buildId, "buildId", MAX_BUILD_ID_LENGTH)
        requireOptionalText(applicationPackage, "applicationPackage")
        requireConfig(batchSize in 1..MAX_BATCH_SIZE) {
            "batchSize must be between 1 and $MAX_BATCH_SIZE"
        }
        requireConfig(maxBatchBytes in MIN_BATCH_BYTES..MAX_BATCH_BYTES) {
            "maxBatchBytes must be between 16 KiB and 1 MiB"
        }
        requireConfig(uploadIntervalMillis > 0) { "uploadIntervalMillis must be positive" }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 20
        const val DEFAULT_MAX_BATCH_BYTES = 512 * 1024
        const val DEFAULT_UPLOAD_INTERVAL_MILLIS = 30_000L
        const val MAX_BATCH_SIZE = 50
        const val MIN_BATCH_BYTES = 16 * 1024
        const val MAX_BATCH_BYTES = 1024 * 1024
        const val MAX_BUILD_ID_LENGTH = 256
    }
}

/** 线上卡顿采集和 ZIP 上传配置。 */
data class JankConfig(
    val enabled: Boolean = true,
    val bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES,
    val minSampleIntervalMillis: Long = DEFAULT_MIN_SAMPLE_INTERVAL_MILLIS,
    val diskQuotaBytes: Long = DEFAULT_DISK_QUOTA_BYTES,
    val artifactTtlMillis: Long = DEFAULT_ARTIFACT_TTL_MILLIS,
    val maxArtifactBytes: Long = DEFAULT_MAX_ARTIFACT_BYTES,
    val foregroundOnly: Boolean = true,
    val enableJniHook: Boolean = false,
    val enableObjectAllocation: Boolean = false,
    val enableWakeup: Boolean = false,
    val enableRusage: Boolean = false,
    val enableStackCaptureStats: Boolean = false,
    val mappingId: String = "",
    val buildId: String? = null,
    val uploadIntervalMillis: Long = DEFAULT_UPLOAD_INTERVAL_MILLIS,
    val fps: FpsConfig = FpsConfig(),
) {
    init {
        requireConfig(bufferSizeBytes in MIN_BUFFER_SIZE_BYTES..MAX_BUFFER_SIZE_BYTES) {
            "bufferSizeBytes must be between 1 MiB and 16 MiB"
        }
        requireConfig(minSampleIntervalMillis >= MIN_SAMPLE_INTERVAL_MILLIS) {
            "minSampleIntervalMillis must be at least 5ms"
        }
        requireConfig(minSampleIntervalMillis <= MAX_MIN_SAMPLE_INTERVAL_MILLIS) {
            "minSampleIntervalMillis is too large"
        }
        requireConfig(diskQuotaBytes > 0) { "diskQuotaBytes must be positive" }
        requireConfig(artifactTtlMillis > 0) { "artifactTtlMillis must be positive" }
        requireConfig(maxArtifactBytes in 1..MAX_ARTIFACT_BYTES) {
            "maxArtifactBytes must be between 1 byte and 64 MiB"
        }
        requireConfig(maxArtifactBytes <= diskQuotaBytes) {
            "maxArtifactBytes must not exceed diskQuotaBytes"
        }
        requireConfig(mappingId.length <= MAX_MAPPING_ID_LENGTH) { "mappingId is too long" }
        requireOptionalText(mappingId.takeIf { it.isNotEmpty() }, "mappingId")
        requireOptionalText(buildId, "buildId", MAX_BUILD_ID_LENGTH)
        requireConfig(uploadIntervalMillis > 0) { "uploadIntervalMillis must be positive" }
    }

    /** 将公共配置转换成 RheaTrace 的不可变采集配置。 */
    internal fun toOnlineTraceConfig(
        buildId: String,
        anonymousDeviceId: String,
        environment: String,
        channel: String,
    ): RheaTrace3.OnlineTraceConfig {
        return RheaTrace3.OnlineTraceConfig.builder()
            .setBufferSizeBytes(bufferSizeBytes)
            .setMinSampleIntervalMs(minSampleIntervalMillis)
            .setDiskQuotaBytes(diskQuotaBytes)
            .setArtifactTtlMs(artifactTtlMillis)
            .setMaxArtifactBytes(maxArtifactBytes)
            .setForegroundOnly(foregroundOnly)
            .setEnabled(enabled)
            .setEnableJniHook(enableJniHook)
            .setEnableObjectAllocation(enableObjectAllocation)
            .setEnableWakeup(enableWakeup)
            .setEnableRusage(enableRusage)
            .setEnableStackCaptureStats(enableStackCaptureStats)
            .setMappingId(mappingId)
            .setAnonymousDeviceId(anonymousDeviceId)
            .setBuildId(this.buildId ?: buildId)
            .setEnvironment(environment)
            .setChannel(channel)
            .build()
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE_BYTES = 5 * 1024 * 1024
        const val DEFAULT_MIN_SAMPLE_INTERVAL_MILLIS = 10L
        const val DEFAULT_DISK_QUOTA_BYTES = 20L * 1024L * 1024L
        const val DEFAULT_ARTIFACT_TTL_MILLIS = 3L * 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_ARTIFACT_BYTES = 10L * 1024L * 1024L
        const val MIN_BUFFER_SIZE_BYTES = 1024 * 1024
        const val MAX_BUFFER_SIZE_BYTES = 16 * 1024 * 1024
        const val MIN_SAMPLE_INTERVAL_MILLIS = 5L
        // Rhea 将毫秒转换为纳秒，限制上界避免 Long 溢出后变成负数。
        const val MAX_MIN_SAMPLE_INTERVAL_MILLIS = Long.MAX_VALUE / 1_000_000L
        const val MAX_ARTIFACT_BYTES = 64L * 1024L * 1024L
        const val MAX_MAPPING_ID_LENGTH = 128
        const val MAX_BUILD_ID_LENGTH = 256
        const val DEFAULT_UPLOAD_INTERVAL_MILLIS = 30_000L
    }
}

/** FPS 采集、日志和指标批次上传配置。 */
data class FpsConfig(
    val enabled: Boolean = true,
    val logLevel: FpsLogLevel = FpsLogLevel.OFF,
    val snapshotIntervalMillis: Long = DEFAULT_SNAPSHOT_INTERVAL_MILLIS,
    val uploadIntervalMillis: Long = DEFAULT_UPLOAD_INTERVAL_MILLIS,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val maxBatchBytes: Int = DEFAULT_MAX_BATCH_BYTES,
    val queueDiskQuotaBytes: Long = DEFAULT_QUEUE_DISK_QUOTA_BYTES,
    val eventTtlMillis: Long = DEFAULT_EVENT_TTL_MILLIS,
) {
    init {
        requireConfig(snapshotIntervalMillis > 0) {
            "fps snapshotIntervalMillis must be positive"
        }
        requireConfig(uploadIntervalMillis > 0) {
            "fps uploadIntervalMillis must be positive"
        }
        requireConfig(batchSize in 1..MAX_BATCH_SIZE) {
            "fps batchSize must be between 1 and $MAX_BATCH_SIZE"
        }
        requireConfig(maxBatchBytes in MIN_BATCH_BYTES..MAX_BATCH_BYTES) {
            "fps maxBatchBytes must be between 16 KiB and 1 MiB"
        }
        requireConfig(queueDiskQuotaBytes >= maxBatchBytes) {
            "fps queueDiskQuotaBytes must not be smaller than maxBatchBytes"
        }
        requireConfig(eventTtlMillis > 0) {
            "fps eventTtlMillis must be positive"
        }
    }

    companion object {
        const val DEFAULT_SNAPSHOT_INTERVAL_MILLIS = 5_000L
        const val DEFAULT_UPLOAD_INTERVAL_MILLIS = 30_000L
        const val DEFAULT_BATCH_SIZE = 20
        const val DEFAULT_MAX_BATCH_BYTES = 512 * 1024
        const val DEFAULT_QUEUE_DISK_QUOTA_BYTES = 20L * 1024L * 1024L
        const val DEFAULT_EVENT_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L
        const val MAX_BATCH_SIZE = 50
        const val MIN_BATCH_BYTES = 16 * 1024
        const val MAX_BATCH_BYTES = 1024 * 1024
    }
}

/** FPS 日志等级；默认关闭，避免生产环境逐帧日志带来额外开销。 */
enum class FpsLogLevel {
    OFF,
    SUMMARY,
    VERBOSE,
}

private fun requireOptionalText(value: String?, name: String, maxLength: Int = 512) {
    requireConfig(value == null || (value.isNotBlank() && value.length <= maxLength)) {
        "$name must be non-blank and at most $maxLength characters"
    }
}

private inline fun requireConfig(condition: Boolean, lazyMessage: () -> String) {
    if (!condition) {
        throw PerformanceInitializationException(
            PerformanceInitializationStage.VALIDATION,
            lazyMessage(),
        )
    }
}

private fun requireConfig(condition: Boolean, message: String) {
    if (!condition) {
        throw PerformanceInitializationException(
            PerformanceInitializationStage.VALIDATION,
            message,
        )
    }
}
