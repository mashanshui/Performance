package com.example.nativelib.crash

data class CrashReporterConfig(
    val baseUrl: String,
    val projectKey: String,
    val appId: String,
    val appVersion: String,
    val versionCode: Int,
    val buildId: String,
    val environment: String,
    val channel: String,
    val applicationPackage: String = appId,
    val schemaVersion: Int = 1,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val maxBatchBytes: Int = DEFAULT_MAX_BATCH_BYTES,
    val uploadIntervalMillis: Long = DEFAULT_UPLOAD_INTERVAL_MILLIS,
    val connectTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val readTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val writeTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val enableNetworkLogging: Boolean = false,
) {
    init {
        require(appId.isNotBlank()) { "appId must not be blank" }
        require(appVersion.isNotBlank()) { "appVersion must not be blank" }
        require(versionCode >= 0) { "versionCode must be non-negative" }
        require(buildId.isNotBlank()) { "buildId must not be blank" }
        require(environment.isNotBlank()) { "environment must not be blank" }
        require(channel.isNotBlank()) { "channel must not be blank" }
        require(applicationPackage.isNotBlank()) { "applicationPackage must not be blank" }
        require(schemaVersion == 1) { "Only crash schema version 1 is supported" }
        require(batchSize in 1..50) { "batchSize must be between 1 and 50" }
        require(maxBatchBytes in 16 * 1024..MAX_REQUEST_BYTES) {
            "maxBatchBytes must be between 16 KiB and 1 MiB"
        }
        require(uploadIntervalMillis > 0) { "uploadIntervalMillis must be positive" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        require(writeTimeoutMillis > 0) { "writeTimeoutMillis must be positive" }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 20
        const val DEFAULT_MAX_BATCH_BYTES = 512 * 1024
        const val DEFAULT_UPLOAD_INTERVAL_MILLIS = 30_000L
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L
        const val MAX_REQUEST_BYTES = 1024 * 1024
    }
}
