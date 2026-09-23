package com.shanshui.performance.network

import java.io.IOException

/** 统一传输层结果类型，业务模块自行解释成功与业务错误。 */
sealed interface NetworkResult<out T> {
    /** HTTP 成功且已完成响应解析。 */
    data class Success<T>(
        /** 解析后的业务响应。 */
        val data: T,
        /** 原始 HTTP 状态码。 */
        val statusCode: Int,
    ) : NetworkResult<T>

    /** HTTP 返回错误，是否重试由业务链路决定。 */
    data class HttpError(
        /** 原始 HTTP 状态码。 */
        val statusCode: Int,
        /** 可选响应正文。 */
        val responseBody: String?,
        /** 传输层根据状态码提供的默认重试建议。 */
        val retryable: Boolean,
        /** 服务端 Retry-After 秒数。 */
        val retryAfterSeconds: Long? = null,
    ) : NetworkResult<Nothing>

    /** 请求未获得 HTTP 响应。 */
    data class NetworkError(
        /** 底层 IO 异常。 */
        val exception: IOException,
    ) : NetworkResult<Nothing>

    /** 响应正文无法按约定解析。 */
    data class SerializationError(
        /** 序列化异常。 */
        val exception: Throwable,
    ) : NetworkResult<Nothing>

    /** 未分类的请求异常。 */
    data class UnknownError(
        /** 原始异常。 */
        val exception: Throwable,
    ) : NetworkResult<Nothing>
}
