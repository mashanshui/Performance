package com.example.nativelib.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 网络层运行配置。项目 Key 只用于请求头，不应写入日志。 */
data class NetworkConfig(
    val baseUrl: String,
    val projectKey: String,
    val schemaVersion: Int = 1,
    val connectTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val readTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val writeTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val enableLogging: Boolean = false,
) {
    internal val normalizedBaseUrl: HttpUrl = normalizeBaseUrl(baseUrl)

    init {
        require(projectKey.isNotBlank()) { "projectKey must not be blank" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        require(writeTimeoutMillis > 0) { "writeTimeoutMillis must be positive" }
    }

    override fun toString(): String {
        return "NetworkConfig(" +
            "baseUrl='$baseUrl', " +
            "projectKey=<redacted>, " +
            "schemaVersion=$schemaVersion, " +
            "connectTimeoutMillis=$connectTimeoutMillis, " +
            "readTimeoutMillis=$readTimeoutMillis, " +
            "writeTimeoutMillis=$writeTimeoutMillis, " +
            "enableLogging=$enableLogging)"
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L

        fun normalizeBaseUrl(rawBaseUrl: String): HttpUrl {
            val trimmed = rawBaseUrl.trim()
            require(trimmed.isNotEmpty()) { "baseUrl must not be blank" }

            val withTrailingSlash = if (trimmed.endsWith('/')) trimmed else "$trimmed/"
            val parsed = withTrailingSlash.toHttpUrlOrNull()
            require(parsed != null) { "baseUrl is not a valid URL: $rawBaseUrl" }
            require(parsed.scheme == "http" || parsed.scheme == "https") {
                "baseUrl must use http or https"
            }
            require(parsed.query == null && parsed.fragment == null) {
                "baseUrl must not contain a query or fragment"
            }
            return parsed
        }
    }
}
