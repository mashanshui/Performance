package com.shanshui.performance.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 内存泄漏报告网络客户端。
 *
 * 请求只包含服务端要求的 metadata 和 report 两个 part，原始 HPROF 不在本客户端的接口中出现。
 */
internal class MemoryLeakReportNetworkClient internal constructor(
    private val api: MemoryLeakReportIngestApi,
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) {
    suspend fun upload(
        metadata: MemoryLeakReportMetadata,
        reportFile: File,
    ): NetworkResult<MemoryLeakReportUploadResponse> {
        return try {
            require(reportFile.isFile) { "Memory leak report file does not exist" }
            val metadataBody = gson.toJson(metadata).toRequestBody(JSON_MEDIA_TYPE)
            val reportBody = reportFile.asRequestBody(JSON_MEDIA_TYPE)
            val response = api.ingest(
                metadata = MultipartBody.Part.createFormData(
                    "metadata",
                    "metadata.json",
                    metadataBody,
                ),
                report = MultipartBody.Part.createFormData(
                    "report",
                    reportFile.name,
                    reportBody,
                ),
            )
            if (response.isSuccessful) {
                response.body()?.let { NetworkResult.Success(it, response.code()) }
                    ?: NetworkResult.SerializationError(
                        IllegalStateException("Successful memory leak response has no body"),
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
        const val MAX_ERROR_BODY_LENGTH = 8 * 1024
        const val RETRY_AFTER_HEADER = "Retry-After"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // 服务端契约明确要求 503 重试；408 代表请求超时，也保留本地任务等待重试。
        fun isRetryableStatus(statusCode: Int): Boolean {
            return statusCode == 408 || statusCode == 503
        }
    }
}
