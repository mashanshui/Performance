package com.shanshui.performance.network

import java.io.IOException

sealed interface NetworkResult<out T> {
    data class Success<T>(
        val data: T,
        val statusCode: Int,
    ) : NetworkResult<T>

    data class HttpError(
        val statusCode: Int,
        val responseBody: String?,
        val retryable: Boolean,
        val retryAfterSeconds: Long? = null,
    ) : NetworkResult<Nothing>

    data class NetworkError(
        val exception: IOException,
    ) : NetworkResult<Nothing>

    data class SerializationError(
        val exception: Throwable,
    ) : NetworkResult<Nothing>

    data class UnknownError(
        val exception: Throwable,
    ) : NetworkResult<Nothing>
}
