package com.example.nativelib.memory.leak

import com.example.nativelib.network.MemoryLeakReportMetadata
import com.example.nativelib.network.MemoryLeakReportUploadResponse
import com.example.nativelib.network.NetworkResult
import kotlin.math.min
import java.io.File

internal fun interface MemoryLeakReportSender {
    suspend fun send(
        metadata: MemoryLeakReportMetadata,
        reportFile: File,
    ): NetworkResult<MemoryLeakReportUploadResponse>
}

internal data class MemoryLeakReportFlushReport(
    val reportsAcknowledged: Int = 0,
    val reportsRetried: Int = 0,
    val reportsDeadLettered: Int = 0,
)

/** 单报告 multipart 上传的重试和响应确认逻辑。 */
internal class MemoryLeakReportUploader(
    private val store: MemoryLeakReportStore,
    private val sender: MemoryLeakReportSender,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val logger: (() -> String) -> Unit = {},
) {
    suspend fun flush(maxReports: Int = MAX_REPORTS_PER_FLUSH): MemoryLeakReportFlushReport {
        require(maxReports > 0) { "maxReports must be positive" }
        store.expireOlderThan(clock() - MemoryLeakReportLimits.EVENT_TTL_MILLIS)
        var result = MemoryLeakReportFlushReport()
        repeat(maxReports) {
            val item = store.nextReady(clock()) ?: return result
            if (!item.reportFile.isFile) {
                store.moveToDeadLetter(item, "report_file_missing")
                result = result.copy(reportsDeadLettered = result.reportsDeadLettered + 1)
                return@repeat
            }
            val sendResult = sender.send(item.record.metadata, item.reportFile)
            val update = handleResult(item, sendResult)
            result = result.copy(
                reportsAcknowledged = result.reportsAcknowledged + update.reportsAcknowledged,
                reportsRetried = result.reportsRetried + update.reportsRetried,
                reportsDeadLettered = result.reportsDeadLettered + update.reportsDeadLettered,
            )
            if (update.reportsRetried > 0 || sendResult !is NetworkResult.Success) {
                return result
            }
        }
        return result
    }

    private fun handleResult(
        item: MemoryLeakReportQueueItem,
        result: NetworkResult<MemoryLeakReportUploadResponse>,
    ): MemoryLeakReportFlushReport {
        return when (result) {
            is NetworkResult.Success -> handleSuccess(item, result)
            is NetworkResult.HttpError -> {
                if (result.retryable) {
                    retry(item, result.retryAfterSeconds, "http_${result.statusCode}")
                } else {
                    store.moveToDeadLetter(item, "http_${result.statusCode}")
                    MemoryLeakReportFlushReport(reportsDeadLettered = 1)
                }
            }

            is NetworkResult.NetworkError -> retry(item, null, "network_error")
            is NetworkResult.SerializationError -> retry(item, null, "serialization_error")
            is NetworkResult.UnknownError -> retry(item, null, "unknown_error")
        }
    }

    private fun handleSuccess(
        item: MemoryLeakReportQueueItem,
        result: NetworkResult.Success<MemoryLeakReportUploadResponse>,
    ): MemoryLeakReportFlushReport {
        val response = result.data
        val valid = result.statusCode == HTTP_OK &&
            response.eventId == item.record.metadata.eventId &&
            response.status in ACCEPTED_STATUSES
        if (!valid) {
            return retry(item, null, "invalid_success_response")
        }
        store.acknowledge(item.record.metadata.eventId)
        return MemoryLeakReportFlushReport(reportsAcknowledged = 1)
    }

    private fun retry(
        item: MemoryLeakReportQueueItem,
        retryAfterSeconds: Long?,
        reason: String,
    ): MemoryLeakReportFlushReport {
        val nextAttempt = item.record.attempts + 1
        if (nextAttempt >= MemoryLeakReportLimits.MAX_ATTEMPTS) {
            store.moveToDeadLetter(item, "retry_exhausted_$reason")
            return MemoryLeakReportFlushReport(reportsDeadLettered = 1)
        }
        val delayMillis = retryAfterSeconds
            ?.coerceAtLeast(0L)
            ?.coerceAtMost(MemoryLeakReportLimits.MAX_RETRY_DELAY_MILLIS / 1_000L)
            ?.times(1_000L)
            ?: calculateBackoffMillis(item.record.attempts)
        store.markRetry(item, clock() + delayMillis)
        logger {
            "memory leak report retained for retry reason=$reason " +
                "eventId=${item.record.metadata.eventId}"
        }
        return MemoryLeakReportFlushReport(reportsRetried = 1)
    }

    private fun calculateBackoffMillis(attempts: Int): Long {
        val exponent = attempts.coerceIn(0, MemoryLeakReportLimits.MAX_BACKOFF_EXPONENT)
        val base = MemoryLeakReportLimits.BASE_RETRY_DELAY_MILLIS * (1L shl exponent)
        return min(MemoryLeakReportLimits.MAX_RETRY_DELAY_MILLIS, base)
    }

    private companion object {
        const val HTTP_OK = 200
        const val MAX_REPORTS_PER_FLUSH = 5
        val ACCEPTED_STATUSES = setOf("accepted", "duplicate")
    }
}
