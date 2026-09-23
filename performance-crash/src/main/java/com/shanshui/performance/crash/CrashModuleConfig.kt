package com.shanshui.performance.crash

/** Crash 模块独立配置，精简接入不需要创建其他功能配置。 */
data class CrashConfig(
    /** 是否启用 Crash 捕获。 */
    val enabled: Boolean = true,
    /** 可选应用版本覆盖。 */
    val appVersion: String? = null,
    /** 可选版本号覆盖。 */
    val versionCode: Int? = null,
    /** 可选构建标识覆盖。 */
    val buildId: String? = null,
    /** 可选应用包名覆盖。 */
    val applicationPackage: String? = null,
    /** 单批事件数量上限。 */
    val batchSize: Int = 20,
    /** 单批 JSON 大小上限。 */
    val maxBatchBytes: Int = 512 * 1024,
    /** 上传间隔。 */
    val uploadIntervalMillis: Long = 30_000L,
) {
    /** 校验 Crash 上传配置。 */
    init {
        require(appVersion == null || (appVersion.isNotBlank() && appVersion.length <= 512)) {
            "appVersion must be non-blank and at most 512 characters"
        }
        require(versionCode == null || versionCode >= 0) { "versionCode must be non-negative" }
        require(buildId == null || (buildId.isNotBlank() && buildId.length <= 256)) {
            "buildId must be non-blank and at most 256 characters"
        }
        require(applicationPackage == null || applicationPackage.isNotBlank()) {
            "applicationPackage must not be blank"
        }
        require(batchSize in 1..50) { "batchSize must be between 1 and 50" }
        require(maxBatchBytes in 16 * 1024..1024 * 1024) {
            "maxBatchBytes must be between 16 KiB and 1 MiB"
        }
        require(uploadIntervalMillis > 0) { "uploadIntervalMillis must be positive" }
    }
}

/** Crash 内部运行配置，收拢网络和队列字段而不引用其他功能。 */
internal data class CrashServiceConfig(
    /** 服务端协议版本。 */
    val schemaVersion: Int = 2,
    /** 运行环境。 */
    val environment: String = "debug",
    /** 渠道。 */
    val channel: String = "official",
    /** Crash 上传间隔。 */
    val crashUploadIntervalMillis: Long = 30_000L,
    /** Crash 批次大小。 */
    val crashBatchSize: Int = 20,
    /** Crash 批次字节上限。 */
    val crashMaxBatchBytes: Int = 512 * 1024,
    /** 基础地址。 */
    val baseUrl: String,
    /** App Key。 */
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
