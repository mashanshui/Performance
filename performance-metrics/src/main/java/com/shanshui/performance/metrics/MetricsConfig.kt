package com.shanshui.performance.metrics

/** FPS 日志等级；默认关闭，避免逐帧日志带来额外开销。 */
enum class FpsLogLevel {
    /** 不输出 FPS 日志。 */
    OFF,
    /** 输出周期汇总。 */
    SUMMARY,
    /** 输出详细采样信息。 */
    VERBOSE,
}

/** FPS 采集、日志和指标批次上传配置。 */
data class FpsConfig(
    /** 是否启用 FPS。 */
    val enabled: Boolean = true,
    /** FPS 日志等级。 */
    val logLevel: FpsLogLevel = FpsLogLevel.OFF,
    /** 快照间隔。 */
    val snapshotIntervalMillis: Long = 5_000L,
    /** 上传间隔。 */
    val uploadIntervalMillis: Long = 30_000L,
    /** 单批事件数量上限。 */
    val batchSize: Int = 20,
    /** 单批 JSON 大小上限。 */
    val maxBatchBytes: Int = 512 * 1024,
    /** 队列磁盘配额。 */
    val queueDiskQuotaBytes: Long = 20L * 1024L * 1024L,
    /** 事件保留时间。 */
    val eventTtlMillis: Long = 7L * 24L * 60L * 60L * 1_000L,
) {
    /** 校验 FPS 配置范围。 */
    init {
        require(snapshotIntervalMillis > 0) { "fps snapshotIntervalMillis must be positive" }
        require(uploadIntervalMillis > 0) { "fps uploadIntervalMillis must be positive" }
        require(batchSize in 1..50) { "fps batchSize must be between 1 and 50" }
        require(maxBatchBytes in 16 * 1024..1024 * 1024) {
            "fps maxBatchBytes must be between 16 KiB and 1 MiB"
        }
        require(queueDiskQuotaBytes >= maxBatchBytes) {
            "fps queueDiskQuotaBytes must not be smaller than maxBatchBytes"
        }
        require(eventTtlMillis > 0) { "fps eventTtlMillis must be positive" }
    }

    /** FPS 队列默认值常量。 */
    companion object {
        /** 默认事件保留时间。 */
        const val DEFAULT_EVENT_TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        /** 默认队列磁盘配额。 */
        const val DEFAULT_QUEUE_DISK_QUOTA_BYTES = 20L * 1024L * 1024L
    }
}

/** Android 进程内存采集和批量上报配置。 */
data class MemoryConfig(
    /** 是否启用内存指标。 */
    val enabled: Boolean = true,
    /** 前台采样间隔。 */
    val foregroundSamplingIntervalMillis: Long = 60_000L,
    /** 后台采样间隔。 */
    val backgroundSamplingIntervalMillis: Long = 5L * 60L * 1_000L,
    /** 上传间隔。 */
    val uploadIntervalMillis: Long = 30_000L,
    /** 单批事件数量上限。 */
    val batchSize: Int = 20,
    /** 单批 JSON 大小上限。 */
    val maxBatchBytes: Int = 512 * 1024,
    /** 队列磁盘配额。 */
    val queueDiskQuotaBytes: Long = 20L * 1024L * 1024L,
    /** 事件保留时间。 */
    val eventTtlMillis: Long = 7L * 24L * 60L * 60L * 1_000L,
    /** 单条事件最大重试次数。 */
    val maxAttempts: Int = 10,
) {
    /** 校验内存指标配置范围。 */
    init {
        require(foregroundSamplingIntervalMillis > 0) {
            "memory foregroundSamplingIntervalMillis must be positive"
        }
        require(backgroundSamplingIntervalMillis > 0) {
            "memory backgroundSamplingIntervalMillis must be positive"
        }
        require(uploadIntervalMillis > 0) { "memory uploadIntervalMillis must be positive" }
        require(batchSize in 1..50) { "memory batchSize must be between 1 and 50" }
        require(maxBatchBytes in 16 * 1024..1024 * 1024) {
            "memory maxBatchBytes must be between 16 KiB and 1 MiB"
        }
        require(queueDiskQuotaBytes >= maxBatchBytes) {
            "memory queueDiskQuotaBytes must not be smaller than maxBatchBytes"
        }
        require(eventTtlMillis > 0) { "memory eventTtlMillis must be positive" }
        require(maxAttempts in 1..100) { "memory maxAttempts must be between 1 and 100" }
    }

    /** 内存队列默认值常量。 */
    companion object {
        /** 默认事件保留时间。 */
        const val DEFAULT_EVENT_TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        /** 默认队列磁盘配额。 */
        const val DEFAULT_QUEUE_DISK_QUOTA_BYTES = 20L * 1024L * 1024L
    }
}

/** Metrics 模块所需的共享环境和功能开关，独立于 Crash 配置。 */
data class MetricsServiceConfig(
    /** 服务端运行环境。 */
    val environment: String = "debug",
    /** 服务端渠道。 */
    val channel: String = "official",
    /** 是否允许 FPS reporter 工作。 */
    val fpsEnabled: Boolean = true,
    /** FPS 队列事件过期时间。 */
    val fpsEventTtlMillis: Long = FpsConfig.DEFAULT_EVENT_TTL_MILLIS,
    /** FPS 队列磁盘配额。 */
    val fpsQueueDiskQuotaBytes: Long = FpsConfig.DEFAULT_QUEUE_DISK_QUOTA_BYTES,
    /** 是否允许内存 reporter 工作。 */
    val memoryEnabled: Boolean = true,
    /** 内存队列事件过期时间。 */
    val memoryEventTtlMillis: Long = MemoryConfig.DEFAULT_EVENT_TTL_MILLIS,
    /** 内存队列磁盘配额。 */
    val memoryQueueDiskQuotaBytes: Long = MemoryConfig.DEFAULT_QUEUE_DISK_QUOTA_BYTES,
    /** 内存事件单条最大重试次数。 */
    val memoryMaxAttempts: Int = 10,
) {
    /** 校验精简 Metrics 接入不依赖其他功能配置。 */
    init {
        require(environment.isNotBlank()) { "metrics environment must not be blank" }
        require(channel.isNotBlank()) { "metrics channel must not be blank" }
        require(fpsEventTtlMillis > 0 && fpsQueueDiskQuotaBytes > 0) {
            "metrics FPS queue settings must be positive"
        }
        require(memoryEventTtlMillis > 0 && memoryQueueDiskQuotaBytes > 0) {
            "metrics memory queue settings must be positive"
        }
        require(memoryMaxAttempts in 1..100) {
            "metrics memory max attempts must be between 1 and 100"
        }
    }
}
