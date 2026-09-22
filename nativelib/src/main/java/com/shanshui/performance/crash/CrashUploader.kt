package com.shanshui.performance.crash

import com.shanshui.performance.network.BatchError
import com.shanshui.performance.network.CrashBatchRequest
import com.shanshui.performance.network.CrashBatchResponse
import com.shanshui.performance.network.NetworkResult
import com.shanshui.performance.config.NativeServiceConfig
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.util.UUID

internal fun interface CrashBatchSender {
    suspend fun send(request: CrashBatchRequest): NetworkResult<CrashBatchResponse>
}

internal data class CrashFlushReport(
    val batchesSent: Int = 0,
    val eventsAcknowledged: Int = 0,
    val eventsRetried: Int = 0,
    val eventsDeadLettered: Int = 0,
)

internal class CrashUploader(
    private val queue: FileCrashQueue,
    private val sender: CrashBatchSender,
    private val config: NativeServiceConfig,
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val requestIdGenerator: () -> String = { UUID.randomUUID().toString() },
    private val logger: (String) -> Unit = {},
) {
    suspend fun flush(maxBatches: Int = MAX_BATCHES_PER_FLUSH): CrashFlushReport {
        require(maxBatches > 0) { "maxBatches must be positive" }
        queue.expireOlderThan(clock() - MAX_EVENT_AGE_MILLIS)

        var report = CrashFlushReport()
        repeat(maxBatches) {
            val selected = selectBatch(clock()) ?: return report
            val result = sender.send(selected.request)
            val update = handleResult(selected, result)
            report = report + update
            if (update.eventsRetried > 0 || result !is NetworkResult.Success) {
                return report
            }
        }
        return report
    }

    private fun selectBatch(nowMillis: Long): SelectedBatch? {
        val candidates = queue.peekBatch(config.crashBatchSize, nowMillis)
        if (candidates.isEmpty()) {
            return null
        }

        val requestId = requestIdGenerator()
        val selected = mutableListOf<CrashQueueItem>()
        for (candidate in candidates) {
            val tentativeEvents = selected.map { it.record.event } + candidate.record.event
            val tentativeRequest = CrashBatchRequest(requestId, tentativeEvents)
            val size = gson.toJson(tentativeRequest).toByteArray(StandardCharsets.UTF_8).size
            if (size > config.crashMaxBatchBytes) {
                if (selected.isEmpty()) {
                    queue.moveToDeadLetter(listOf(candidate), "event_too_large")
                    logger("crash event moved to dead letter: size=$size")
                    continue
                }
                break
            }
            selected += candidate
        }

        if (selected.isEmpty()) {
            return null
        }
        return SelectedBatch(
            request = CrashBatchRequest(
                requestId = requestId,
                events = selected.map { it.record.event },
            ),
            items = selected,
        )
    }

    private fun handleResult(
        batch: SelectedBatch,
        result: NetworkResult<CrashBatchResponse>,
    ): CrashFlushReport {
        return when (result) {
            is NetworkResult.Success -> handleSuccess(batch, result.data)
            is NetworkResult.HttpError -> handleHttpError(batch, result)
            is NetworkResult.NetworkError -> {
                retry(batch.items, null, "network_error:${result.exception.javaClass.simpleName}")
            }
            is NetworkResult.SerializationError -> {
                retry(batch.items, null, "serialization_error:${result.exception.javaClass.simpleName}")
            }
            is NetworkResult.UnknownError -> {
                retry(batch.items, null, "unknown_error:${result.exception.javaClass.simpleName}")
            }
        }
    }

    private fun handleSuccess(
        batch: SelectedBatch,
        response: CrashBatchResponse,
    ): CrashFlushReport {
        if (!isValidSuccessResponse(batch, response)) {
            return retry(batch.items, response.retryAfterSeconds, "invalid_success_response")
        }
        if (response.retryable && (
                response.errors.isEmpty() ||
                    response.errors.any { it.index == null && it.eventId == null }
                )
        ) {
            return retry(batch.items, response.retryAfterSeconds, "server_retryable_response")
        }

        val eventIndexById = batch.request.events
            .mapIndexed { index, event -> event.eventId to index }
            .toMap()
        val retryIndices = mutableSetOf<Int>()
        val permanentIndices = mutableSetOf<Int>()

        for (error in response.errors) {
            val index = resolveErrorIndex(error, batch.request.events, eventIndexById)
            if (index == null) {
                return retry(batch.items, response.retryAfterSeconds, "invalid_response_error_index")
            }
            if (error.retryable) {
                retryIndices += index
            } else {
                permanentIndices += index
            }
        }

        val retryItems = retryIndices
            .filter { it !in permanentIndices }
            .map { batch.items[it] }
        val permanentItems = permanentIndices.map { batch.items[it] }
        val acknowledgedItems = batch.items.filterIndexed { index, _ ->
            index !in retryIndices && index !in permanentIndices
        }

        if (permanentItems.isNotEmpty()) {
            val codes = response.errors
                .filter { !it.retryable }
                .mapNotNull { it.code }
                .distinct()
                .joinToString(",")
                .ifBlank { "permanent_event_error" }
            queue.moveToDeadLetter(permanentItems, "server_rejected:$codes")
        }
        queue.acknowledge(acknowledgedItems.map { it.record.event.eventId })
        if (retryItems.isNotEmpty()) {
            val retryReport = retry(retryItems, response.retryAfterSeconds, "server_event_retryable")
            return CrashFlushReport(
                batchesSent = 1,
                eventsAcknowledged = acknowledgedItems.size,
                eventsRetried = retryReport.eventsRetried,
                eventsDeadLettered = permanentItems.size,
            )
        }

        return CrashFlushReport(
            batchesSent = 1,
            eventsAcknowledged = acknowledgedItems.size,
            eventsDeadLettered = permanentItems.size,
        )
    }

    /** 校验服务端成功响应的请求标识、计数和错误定位，避免误删未确认事件。 */
    private fun isValidSuccessResponse(
        batch: SelectedBatch,
        response: CrashBatchResponse,
    ): Boolean {
        if (response.requestId != batch.request.requestId) {
            return false
        }
        if (response.accepted < 0 || response.rejected < 0 || response.duplicate < 0) {
            return false
        }

        // 服务端的三类计数必须完整覆盖当前批次。
        val reportedTotal = response.accepted.toLong() +
            response.rejected.toLong() +
            response.duplicate.toLong()
        if (reportedTotal != batch.request.events.size.toLong()) {
            return false
        }

        // 一条事件允许对应多条错误，因此按事件索引去重后与 rejected 对比。
        val eventIndexById = batch.request.events
            .mapIndexed { index, event -> event.eventId to index }
            .toMap()
        val rejectedIndices = mutableSetOf<Int>()
        for (error in response.errors) {
            val index = resolveErrorIndex(error, batch.request.events, eventIndexById)
                ?: return false
            rejectedIndices += index
        }
        return response.rejected == rejectedIndices.size
    }

    private fun handleHttpError(
        batch: SelectedBatch,
        error: NetworkResult.HttpError,
    ): CrashFlushReport {
        val body = parseHttpError(error.responseBody)
        val retryable = body?.retryable ?: error.retryable
        val retryAfterSeconds = error.retryAfterSeconds ?: body?.retryAfterSeconds
        if (retryable) {
            return retry(batch.items, retryAfterSeconds, "http_${error.statusCode}")
        }
        queue.moveToDeadLetter(batch.items, "http_${error.statusCode}")
        logger("crash batch moved to dead letter: status=${error.statusCode}")
        return CrashFlushReport(
            batchesSent = 1,
            eventsDeadLettered = batch.items.size,
        )
    }

    private fun retry(
        items: List<CrashQueueItem>,
        retryAfterSeconds: Long?,
        reason: String,
    ): CrashFlushReport {
        if (items.isEmpty()) {
            return CrashFlushReport()
        }
        val delayMillis = retryAfterSeconds
            ?.coerceAtLeast(0L)
            ?.coerceAtMost(MAX_RETRY_DELAY_MILLIS / 1000)
            ?.times(1000L)
            ?: calculateBackoffMillis(items)
        queue.markRetry(items, clock() + delayMillis)
        logger("crash batch retained for retry: reason=$reason count=${items.size}")
        return CrashFlushReport(eventsRetried = items.size)
    }

    private fun calculateBackoffMillis(items: List<CrashQueueItem>): Long {
        val maxAttempts = items.maxOf { it.record.attempts }.coerceIn(0, MAX_BACKOFF_EXPONENT)
        return (BASE_RETRY_DELAY_MILLIS * (1L shl maxAttempts))
            .coerceAtMost(MAX_RETRY_DELAY_MILLIS)
    }

    private fun resolveErrorIndex(
        error: BatchError,
        events: List<com.shanshui.performance.network.CrashEvent>,
        eventIndexById: Map<String, Int>,
    ): Int? {
        val index = error.index
        if (index != null) {
            if (index !in events.indices) {
                return null
            }
            if (error.eventId != null && error.eventId != events[index].eventId) {
                return null
            }
            return index
        }
        return error.eventId?.let(eventIndexById::get)
    }

    private fun parseHttpError(body: String?): HttpErrorPayload? {
        if (body.isNullOrBlank()) {
            return null
        }
        return runCatching { gson.fromJson(body, HttpErrorPayload::class.java) }.getOrNull()
    }

    private data class SelectedBatch(
        val request: CrashBatchRequest,
        val items: List<CrashQueueItem>,
    )

    private data class HttpErrorPayload(
        val retryable: Boolean? = null,
        val retryAfterSeconds: Long? = null,
    )

    private operator fun CrashFlushReport.plus(other: CrashFlushReport): CrashFlushReport {
        return CrashFlushReport(
            batchesSent = batchesSent + other.batchesSent,
            eventsAcknowledged = eventsAcknowledged + other.eventsAcknowledged,
            eventsRetried = eventsRetried + other.eventsRetried,
            eventsDeadLettered = eventsDeadLettered + other.eventsDeadLettered,
        )
    }

    private companion object {
        const val MAX_BATCHES_PER_FLUSH = 5
        const val MAX_EVENT_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val BASE_RETRY_DELAY_MILLIS = 30_000L
        const val MAX_RETRY_DELAY_MILLIS = 60L * 60 * 1000
        const val MAX_BACKOFF_EXPONENT = 7
    }
}
