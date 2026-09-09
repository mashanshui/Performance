package com.example.nativelib.network

import com.example.nativelib.memory.MemoryBatchSender
import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import java.io.IOException
import java.util.concurrent.CancellationException

/** 内存指标网络客户端，将异常转换为队列可处理的统一结果。 */
internal class MemoryNetworkClient internal constructor(
    private val api: MemoryIngestApi,
) {
    suspend fun sendBatch(request: MemoryBatchRequest): NetworkResult<MemoryBatchResponse> {
        return try {
            val response = api.ingest(MEMORY_SCHEMA_VERSION, request)
            if (response.isSuccessful) {
                response.body()?.let { NetworkResult.Success(it, response.code()) }
                    ?: NetworkResult.SerializationError(
                        IllegalStateException("Successful memory response has no body"),
                    )
            } else {
                NetworkResult.HttpError(
                    statusCode = response.code(),
                    responseBody = response.errorBody()?.string()?.take(MAX_ERROR_BODY_LENGTH),
                    retryable = isRetryableStatus(response.code()),
                    retryAfterSeconds = response.headers()[RETRY_AFTER_HEADER]
                        ?.trim()
                        ?.toLongOrNull()
                        ?.coerceAtLeast(0L),
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: JsonParseException) {
            NetworkResult.SerializationError(exception)
        } catch (exception: MalformedJsonException) {
            NetworkResult.SerializationError(exception)
        } catch (exception: IOException) {
            NetworkResult.NetworkError(exception)
        } catch (exception: Exception) {
            NetworkResult.UnknownError(exception)
        }
    }

    private companion object {
        const val MEMORY_SCHEMA_VERSION = 2
        const val MAX_ERROR_BODY_LENGTH = 8 * 1024
        const val RETRY_AFTER_HEADER = "Retry-After"

        fun isRetryableStatus(statusCode: Int): Boolean {
            return statusCode == 408 || statusCode == 429 || statusCode in 500..599
        }
    }
}

/** 绑定统一网络工厂中的内存网络客户端。 */
internal fun MemoryNetworkClient.asMemoryBatchSender(): MemoryBatchSender {
    return MemoryBatchSender { request -> sendBatch(request) }
}
