package com.example.nativelib.network

import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import java.io.IOException
import java.util.concurrent.CancellationException

/** FPS 指标网络客户端；所有异常都转换成统一的网络结果供持久队列重试。 */
internal class FpsNetworkClient internal constructor(
    private val api: FpsIngestApi,
) {
    /** 发送一个 FPS 批次，并隐藏服务端错误正文避免日志泄露业务数据。 */
    suspend fun sendBatch(request: FpsBatchRequest): NetworkResult<FpsBatchResponse> {
        return try {
            val response = api.ingest(FPS_SCHEMA_VERSION, request)
            if (response.isSuccessful) {
                val body = response.body()
                if (body == null) {
                    NetworkResult.SerializationError(
                        IllegalStateException("Successful FPS response has no body"),
                    )
                } else {
                    NetworkResult.Success(body, response.code())
                }
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
        const val FPS_SCHEMA_VERSION = 2
        const val MAX_ERROR_BODY_LENGTH = 8 * 1024
        const val RETRY_AFTER_HEADER = "Retry-After"

        /** 判断 HTTP 状态是否应该保留事件并稍后重试。 */
        fun isRetryableStatus(statusCode: Int): Boolean {
            return statusCode == 408 || statusCode == 429 || statusCode in 500..599
        }
    }
}
