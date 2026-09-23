package com.shanshui.performance.jank

/** 线上卡顿采集和 ZIP 上传配置；Rhea 转换仅在 Jank 模块内完成。 */
data class JankConfig(
    /** 是否启用线上卡顿采集。 */
    val enabled: Boolean = true,
    /** Rhea 环形缓冲区大小。 */
    val bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES,
    /** 最小采样间隔。 */
    val minSampleIntervalMillis: Long = DEFAULT_MIN_SAMPLE_INTERVAL_MILLIS,
    /** 产物磁盘配额。 */
    val diskQuotaBytes: Long = DEFAULT_DISK_QUOTA_BYTES,
    /** 产物保留时间。 */
    val artifactTtlMillis: Long = DEFAULT_ARTIFACT_TTL_MILLIS,
    /** 单个产物最大字节数。 */
    val maxArtifactBytes: Long = DEFAULT_MAX_ARTIFACT_BYTES,
    /** 仅前台采集。 */
    val foregroundOnly: Boolean = true,
    /** 是否启用 JNI hook。 */
    val enableJniHook: Boolean = false,
    /** 是否启用对象分配采集。 */
    val enableObjectAllocation: Boolean = false,
    /** 是否启用唤醒采集。 */
    val enableWakeup: Boolean = false,
    /** 是否启用 rusage。 */
    val enableRusage: Boolean = false,
    /** 是否启用堆栈采集统计。 */
    val enableStackCaptureStats: Boolean = false,
    /** 映射文件标识。 */
    val mappingId: String = "",
    /** 可选配置覆盖构建标识。 */
    val buildId: String? = null,
    /** ZIP 上传间隔。 */
    val uploadIntervalMillis: Long = DEFAULT_UPLOAD_INTERVAL_MILLIS,
) {
    /** 校验 Rhea 配置范围。 */
    init {
        require(bufferSizeBytes in MIN_BUFFER_SIZE_BYTES..MAX_BUFFER_SIZE_BYTES) {
            "bufferSizeBytes must be between 1 MiB and 16 MiB"
        }
        require(minSampleIntervalMillis in MIN_SAMPLE_INTERVAL_MILLIS..MAX_MIN_SAMPLE_INTERVAL_MILLIS) {
            "minSampleIntervalMillis is outside supported range"
        }
        require(diskQuotaBytes > 0) { "diskQuotaBytes must be positive" }
        require(artifactTtlMillis > 0) { "artifactTtlMillis must be positive" }
        require(maxArtifactBytes in 1..MAX_ARTIFACT_BYTES) {
            "maxArtifactBytes must be between 1 byte and 64 MiB"
        }
        require(maxArtifactBytes <= diskQuotaBytes) {
            "maxArtifactBytes must not exceed diskQuotaBytes"
        }
        require(mappingId.length <= MAX_MAPPING_ID_LENGTH) { "mappingId is too long" }
        require(buildId == null || (buildId.isNotBlank() && buildId.length <= MAX_BUILD_ID_LENGTH)) {
            "buildId must be non-blank and at most $MAX_BUILD_ID_LENGTH characters"
        }
        require(uploadIntervalMillis > 0) { "uploadIntervalMillis must be positive" }
    }

    /** 将 SDK 自有配置转换为 Rhea 配置，第三方类型不离开本模块。 */
    internal fun toOnlineTraceConfig(
        buildId: String,
        anonymousDeviceId: String,
        processId: String,
        environment: String,
        channel: String,
    ): com.bytedance.rheatrace.RheaTrace3.OnlineTraceConfig {
        return com.bytedance.rheatrace.RheaTrace3.OnlineTraceConfig.builder()
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
            .setProcessId(processId)
            .setBuildId(this.buildId ?: buildId)
            .setEnvironment(environment)
            .setChannel(channel)
            .build()
    }

    /** 配置边界常量。 */
    companion object {
        /** 默认缓冲区大小。 */
        const val DEFAULT_BUFFER_SIZE_BYTES = 5 * 1024 * 1024
        /** 默认最小采样间隔。 */
        const val DEFAULT_MIN_SAMPLE_INTERVAL_MILLIS = 10L
        /** 默认磁盘配额。 */
        const val DEFAULT_DISK_QUOTA_BYTES = 20L * 1024L * 1024L
        /** 默认产物保留时间。 */
        const val DEFAULT_ARTIFACT_TTL_MILLIS = 3L * 24L * 60L * 60L * 1_000L
        /** 默认单产物大小。 */
        const val DEFAULT_MAX_ARTIFACT_BYTES = 10L * 1024L * 1024L
        /** 最小缓冲区大小。 */
        const val MIN_BUFFER_SIZE_BYTES = 1024 * 1024
        /** 最大缓冲区大小。 */
        const val MAX_BUFFER_SIZE_BYTES = 16 * 1024 * 1024
        /** 最小采样间隔。 */
        const val MIN_SAMPLE_INTERVAL_MILLIS = 5L
        /** 防止毫秒转纳秒溢出的上限。 */
        const val MAX_MIN_SAMPLE_INTERVAL_MILLIS = Long.MAX_VALUE / 1_000_000L
        /** 单产物大小上限。 */
        const val MAX_ARTIFACT_BYTES = 64L * 1024L * 1024L
        /** 映射标识长度上限。 */
        const val MAX_MAPPING_ID_LENGTH = 128
        /** 构建标识长度上限。 */
        const val MAX_BUILD_ID_LENGTH = 256
        /** 默认上传间隔。 */
        const val DEFAULT_UPLOAD_INTERVAL_MILLIS = 30_000L
    }
}

/** Jank 共享的服务配置，不包含其他功能开关。 */
internal data class JankServiceConfig(
    /** 是否允许 Jank reporter 工作。 */
    val jankEnabled: Boolean = true,
    /** ZIP 上传间隔。 */
    val jankUploadIntervalMillis: Long = 30_000L,
    /** 单个 ZIP 最大字节数。 */
    val jankMaxArtifactBytes: Long = 10L * 1024L * 1024L,
    /** 认证服务地址。 */
    val baseUrl: String,
    /** 请求 App Key。 */
    val appKey: String,
    /** 协议环境。 */
    val environment: String,
    /** 协议渠道。 */
    val channel: String,
    /** 网络超时。 */
    val connectTimeoutMillis: Long = 3_000L,
    /** 网络读取超时。 */
    val readTimeoutMillis: Long = 3_000L,
    /** 网络写入超时。 */
    val writeTimeoutMillis: Long = 3_000L,
    /** 是否启用网络日志。 */
    val enableNetworkLogging: Boolean = false,
)
