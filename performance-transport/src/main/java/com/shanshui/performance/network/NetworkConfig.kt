package com.shanshui.performance.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 网络层运行配置；App Key 只用于请求头，不写入日志。 */
data class NetworkConfig(
    /** 所有功能 API 共用的基础地址。 */
    val baseUrl: String,
    /** 发送到 X-App-Key 的应用凭据。 */
    val appKey: String,
    /** 事件协议版本。 */
    val schemaVersion: Int = 2,
    /** 建立连接超时时间。 */
    val connectTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** 读取响应超时时间。 */
    val readTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** 写入请求超时时间。 */
    val writeTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** 是否启用基础请求日志。 */
    val enableLogging: Boolean = false,
) {
    /** 经过协议校验且带尾斜杠的 Retrofit 基础地址。 */
    internal val normalizedBaseUrl: HttpUrl = normalizeBaseUrl(baseUrl)

    /** 校验网络配置和超时参数。 */
    init {
        require(appKey.isNotBlank()) { "appKey must not be blank" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        require(writeTimeoutMillis > 0) { "writeTimeoutMillis must be positive" }
    }

    /** 返回脱敏后的配置，避免凭据进入诊断日志。 */
    override fun toString(): String {
        val safeBaseUrl = baseUrl.replace(appKey, "<redacted>")
        return "NetworkConfig(" +
            "baseUrl='$safeBaseUrl', " +
            "appKey=<redacted>, " +
            "schemaVersion=$schemaVersion, " +
            "connectTimeoutMillis=$connectTimeoutMillis, " +
            "readTimeoutMillis=$readTimeoutMillis, " +
            "writeTimeoutMillis=$writeTimeoutMillis, " +
            "enableLogging=$enableLogging)"
    }

    /** 网络默认配置常量和基础地址校验逻辑。 */
    private companion object {
        /** 现有 SDK 保持的默认请求超时时间。 */
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L

        /** 将原始地址规范化并限制为 HTTP/HTTPS。 */
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
