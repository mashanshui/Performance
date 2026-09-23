package com.shanshui.performance.memory.leak

/** Activity 泄漏检测与 KOOM dump 联动配置。 */
data class MemoryLeakConfig(
    /** 是否启用 Activity 检测；Leak 注册时分析进程准备仍会执行。 */
    val enabled: Boolean = true,
    /** 前台扫描间隔。 */
    val foregroundScanIntervalMillis: Long = 60_000L,
    /** 后台扫描间隔。 */
    val backgroundScanIntervalMillis: Long = 20L * 60L * 1_000L,
    /** 每个 Activity 的最大重检次数。 */
    val maxRecheckCount: Int = 10,
    /** GC 到重检的延迟。 */
    val gcDelayMillis: Long = 2_000L,
    /** 调试器连接时是否跳过检测。 */
    val skipWhenDebuggerConnected: Boolean = true,
) {
    /** 校验 Activity 泄漏配置范围。 */
    init {
        require(foregroundScanIntervalMillis in 1L..MAX_SCAN_INTERVAL_MILLIS) {
            "memoryLeak foregroundScanIntervalMillis is outside supported range"
        }
        require(backgroundScanIntervalMillis in 1L..MAX_SCAN_INTERVAL_MILLIS) {
            "memoryLeak backgroundScanIntervalMillis is outside supported range"
        }
        require(maxRecheckCount in 1..MAX_RECHECK_COUNT) {
            "memoryLeak maxRecheckCount must be between 1 and $MAX_RECHECK_COUNT"
        }
        require(gcDelayMillis in 1L..MAX_GC_DELAY_MILLIS) {
            "memoryLeak gcDelayMillis is outside supported range"
        }
    }

    /** Leak 配置边界常量。 */
    companion object {
        /** 扫描间隔最大值。 */
        const val MAX_SCAN_INTERVAL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        /** 重检次数最大值。 */
        const val MAX_RECHECK_COUNT = 100
        /** GC 延迟最大值。 */
        const val MAX_GC_DELAY_MILLIS = 5L * 60L * 1_000L
    }
}

/** Leak report 上传所需的共享服务配置。 */
internal data class LeakServiceConfig(
    /** 服务端运行环境。 */
    val environment: String,
    /** 服务端渠道。 */
    val channel: String,
    /** Leak report 上传地址。 */
    val baseUrl: String,
    /** Leak report App Key。 */
    val appKey: String,
    /** 建连超时。 */
    val connectTimeoutMillis: Long = 3_000L,
    /** 读取超时。 */
    val readTimeoutMillis: Long = 3_000L,
    /** 写入超时。 */
    val writeTimeoutMillis: Long = 3_000L,
    /** 是否启用网络日志。 */
    val enableNetworkLogging: Boolean = false,
)
