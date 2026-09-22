package com.shanshui.performance.network

import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody

class JankArtifactNetworkClient internal constructor(
    private val api: JankArtifactIngestApi,
) {
    suspend fun upload(artifact: File): NetworkResult<JankArtifactUploadResponse> {
        return try {
            val response = api.ingest(artifact.asRequestBody(JANK_ARTIFACT_MEDIA_TYPE))
            if (response.code() == HTTP_OK) {
                val body = response.body()
                if (body == null) {
                    NetworkResult.SerializationError(
                        IllegalStateException("Successful response has no body"),
                    )
                } else {
                    NetworkResult.Success(body, response.code())
                }
            } else {
                NetworkResult.HttpError(
                    statusCode = response.code(),
                    responseBody = response.errorBody()?.string()?.take(MAX_ERROR_BODY_LENGTH),
                    retryable = response.code() == 503,
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
        /** 服务端对 Jank 原始 ZIP 的唯一成功状态。 */
        const val HTTP_OK = 200
        const val MAX_ERROR_BODY_LENGTH = 8 * 1024
        const val RETRY_AFTER_HEADER = "Retry-After"
        val JANK_ARTIFACT_MEDIA_TYPE =
            "application/vnd.shanshui.rheajank+zip".toMediaType()
    }
}
