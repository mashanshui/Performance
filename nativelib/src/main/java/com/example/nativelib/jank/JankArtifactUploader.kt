package com.example.nativelib.jank

import com.example.nativelib.network.JankArtifactUploadResponse
import com.example.nativelib.network.NetworkResult
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

internal fun interface JankArtifactSender {
    suspend fun upload(artifact: File): NetworkResult<JankArtifactUploadResponse>
}

internal interface JankArtifactStore {
    fun pendingArtifacts(): List<File>

    fun delete(artifact: File): Boolean
}

internal data class JankFlushReport(
    val uploaded: Int = 0,
    val deleted: Int = 0,
    val retried: Int = 0,
    val permanentlyRejected: Int = 0,
)

internal class JankArtifactUploader(
    private val queue: FileJankUploadQueue,
    private val store: JankArtifactStore,
    private val sender: JankArtifactSender,
    private val config: JankUploadConfig,
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val logger: (String) -> Unit = {},
) {
    suspend fun flush(maxArtifacts: Int = MAX_ARTIFACTS_PER_FLUSH): JankFlushReport {
        require(maxArtifacts > 0) { "maxArtifacts must be positive" }
        var report = JankFlushReport()
        repeat(maxArtifacts) {
            val pendingArtifacts = store.pendingArtifacts()
            queue.reconcile(pendingArtifacts, clock())
            val item = queue.nextReady(clock()) ?: return report
            val artifact = pendingArtifacts.firstOrNull { file ->
                file.name == item.record.fileName
            } ?: run {
                queue.remove(item.record.eventId)
                return@repeat
            }

            if (item.record.deletePending) {
                report += deleteAcknowledgedArtifact(item, artifact)
                return@repeat
            }

            val validationCode = validateArtifact(artifact)
            if (validationCode != null) {
                log(item.record.eventId, null, validationCode, "permanent_failure")
                queue.markDeletePending(item)
                val deleteItem = queue.find(item.record.eventId) ?: return report
                report += deleteAcknowledgedArtifact(
                    deleteItem,
                    artifact,
                ).copy(permanentlyRejected = 1)
                return@repeat
            }

            val result = sender.upload(artifact)
            val update = handleResult(item, artifact, result)
            report += update
            if (update.retried > 0) {
                return report
            }
        }
        return report
    }

    private fun validateArtifact(artifact: File): String? {
        return when {
            !FileJankUploadQueue.validArtifactName(artifact.name) -> "INVALID_ARTIFACT_NAME"
            !artifact.isFile -> "ARTIFACT_NOT_FOUND"
            artifact.length() <= 0 -> "EMPTY_ARTIFACT"
            artifact.length() > config.maxArtifactBytes -> "PAYLOAD_TOO_LARGE"
            else -> null
        }
    }

    private fun handleResult(
        item: JankQueueItem,
        artifact: File,
        result: NetworkResult<JankArtifactUploadResponse>,
    ): JankFlushReport {
        return when (result) {
            is NetworkResult.Success -> handleSuccess(item, artifact, result)
            is NetworkResult.HttpError -> handleHttpError(item, artifact, result)
            is NetworkResult.NetworkError -> retry(item, null, "NETWORK_ERROR")
            is NetworkResult.SerializationError -> retry(item, null, "INVALID_RESPONSE")
            is NetworkResult.UnknownError -> retry(item, null, "UNKNOWN_ERROR")
        }
    }

    private fun handleSuccess(
        item: JankQueueItem,
        artifact: File,
        result: NetworkResult.Success<JankArtifactUploadResponse>,
    ): JankFlushReport {
        val response = result.data
        if (!response.success || response.status !in SUCCESS_STATUSES) {
            return retry(item, null, "INVALID_SUCCESS_RESPONSE", result.statusCode)
        }
        log(item.record.eventId, result.statusCode, response.status, "acknowledged")
        queue.markDeletePending(item)
        val deleteItem = queue.find(item.record.eventId)
            ?: return JankFlushReport(uploaded = 1)
        return deleteAcknowledgedArtifact(deleteItem, artifact).copy(uploaded = 1)
    }

    private fun handleHttpError(
        item: JankQueueItem,
        artifact: File,
        error: NetworkResult.HttpError,
    ): JankFlushReport {
        val code = parseErrorCode(error.responseBody) ?: "HTTP_${error.statusCode}"
        if (error.statusCode in PERMANENT_HTTP_STATUSES) {
            log(item.record.eventId, error.statusCode, code, "permanent_failure")
            queue.markDeletePending(item)
            val deleteItem = queue.find(item.record.eventId)
                ?: return JankFlushReport(permanentlyRejected = 1)
            return deleteAcknowledgedArtifact(deleteItem, artifact)
                .copy(permanentlyRejected = 1)
        }
        return retry(item, error.retryAfterSeconds, code, error.statusCode)
    }

    private fun deleteAcknowledgedArtifact(
        item: JankQueueItem,
        artifact: File,
    ): JankFlushReport {
        // 临时测试：保留本地 ZIP，避免再次上传
        log(item.record.eventId, null, "LOCAL_DELETE_SKIPPED_TEST", "retained")
        return retry(item, null, "LOCAL_DELETE_SKIPPED_TEST")
    }

    private fun retry(
        item: JankQueueItem,
        retryAfterSeconds: Long?,
        code: String,
        statusCode: Int? = null,
    ): JankFlushReport {
        val delayMillis = retryAfterSeconds
            ?.coerceAtLeast(0L)
            ?.coerceAtMost(MAX_RETRY_DELAY_MILLIS / 1000L)
            ?.times(1000L)
            ?: calculateBackoffMillis(item.record.attempts)
        queue.markRetry(item, clock() + delayMillis)
        log(item.record.eventId, statusCode, code, "retry")
        return JankFlushReport(retried = 1)
    }

    private fun calculateBackoffMillis(attempts: Int): Long {
        val exponent = attempts.coerceIn(0, MAX_BACKOFF_EXPONENT)
        return (BASE_RETRY_DELAY_MILLIS * (1L shl exponent))
            .coerceAtMost(MAX_RETRY_DELAY_MILLIS)
    }

    private fun parseErrorCode(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) {
            return null
        }
        return runCatching {
            gson.fromJson(responseBody, ErrorPayload::class.java)?.code
                ?.takeIf { code -> code.matches(ERROR_CODE_PATTERN) }
        }.getOrNull()
    }

    private fun log(eventId: String, statusCode: Int?, code: String, outcome: String) {
        logger(
            "jank artifact: eventId=$eventId http=${statusCode ?: "none"} " +
                "code=$code outcome=$outcome",
        )
    }

    private data class ErrorPayload(
        val code: String? = null,
    )

    private operator fun JankFlushReport.plus(other: JankFlushReport): JankFlushReport {
        return JankFlushReport(
            uploaded = uploaded + other.uploaded,
            deleted = deleted + other.deleted,
            retried = retried + other.retried,
            permanentlyRejected = permanentlyRejected + other.permanentlyRejected,
        )
    }

    private companion object {
        const val MAX_ARTIFACTS_PER_FLUSH = 5
        const val BASE_RETRY_DELAY_MILLIS = 30_000L
        const val MAX_RETRY_DELAY_MILLIS = 60L * 60L * 1000L
        const val MAX_BACKOFF_EXPONENT = 7
        val SUCCESS_STATUSES = setOf("accepted", "duplicate")
        val PERMANENT_HTTP_STATUSES = setOf(400, 401, 413, 415, 422)
        val ERROR_CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,127}")
    }
}
