package com.shanshui.performance.memory

import com.shanshui.performance.MemoryConfig
import com.shanshui.performance.network.MemoryBatchError
import com.shanshui.performance.network.MemoryBatchRequest
import com.shanshui.performance.network.MemoryBatchResponse
import com.shanshui.performance.network.MemoryMetricEvent
import com.shanshui.performance.network.NetworkResult
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

internal fun interface MemoryBatchSender {
    suspend fun send(request: MemoryBatchRequest): NetworkResult<MemoryBatchResponse>
}

internal data class MemoryFlushReport(
    val batchesSent: Int = 0,
    val eventsAcknowledged: Int = 0,
    val eventsRetried: Int = 0,
    val eventsDeadLettered: Int = 0,
)

/** 校验服务端部分接受响应，并保持原 eventId 进行重试。 */
internal class MemoryUploader(
    private val store: MemoryEventStore,
    private val sender: MemoryBatchSender,
    private val config: MemoryConfig,
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val requestIdGenerator: () -> String = { UUID.randomUUID().toString() },
    private val random: () -> Int = { Random.nextInt(0, JITTER_BOUND_MILLIS) },
    private val logger: (() -> String) -> Unit = {},
) {
    suspend fun flush(maxBatches: Int = MAX_BATCHES_PER_FLUSH): MemoryFlushReport {
        require(maxBatches > 0) { "maxBatches must be positive" }
        store.expireOlderThan(clock())
        var report = MemoryFlushReport()
        repeat(maxBatches) {
            val batch = selectBatch(clock()) ?: return report
            val result = sender.send(batch.request)
            val update = handleResult(batch, result)
            report += update
            if (update.eventsRetried > 0 || result !is NetworkResult.Success) return report
        }
        return report
    }

    private fun selectBatch(nowMillis: Long): SelectedBatch? {
        val candidates = store.peekBatch(config.batchSize, nowMillis)
        if (candidates.isEmpty()) return null
        val requestId = requestIdGenerator()
        val selected = mutableListOf<MemoryQueueItem>()
        for (candidate in candidates) {
            val tentative = MemoryBatchRequest(
                requestId,
                (selected + candidate).map { it.record.event },
            )
            val size = gson.toJson(tentative).toByteArray(StandardCharsets.UTF_8).size
            if (size > MAX_EVENT_BYTES && selected.isEmpty()) {
                store.moveToDeadLetter(listOf(candidate), "event_too_large")
                continue
            }
            if (size > config.maxBatchBytes) {
                if (selected.isEmpty()) {
                    store.moveToDeadLetter(listOf(candidate), "event_too_large")
                    continue
                }
                break
            }
            selected += candidate
        }
        if (selected.isEmpty()) return null
        return SelectedBatch(
            MemoryBatchRequest(requestId, selected.map { it.record.event }),
            selected,
        )
    }

    private fun handleResult(
        batch: SelectedBatch,
        result: NetworkResult<MemoryBatchResponse>,
    ): MemoryFlushReport {
        return when (result) {
            is NetworkResult.Success -> handleSuccess(batch, result)
            is NetworkResult.HttpError -> if (result.retryable) {
                retryOrDeadLetter(batch.items, result.retryAfterSeconds, "http_${result.statusCode}")
            } else {
                store.moveToDeadLetter(batch.items, "http_${result.statusCode}")
                MemoryFlushReport(batchesSent = 1, eventsDeadLettered = batch.items.size)
            }
            is NetworkResult.NetworkError -> retryOrDeadLetter(batch.items, null, "network_error")
            is NetworkResult.SerializationError -> retryOrDeadLetter(batch.items, null, "serialization_error")
            is NetworkResult.UnknownError -> retryOrDeadLetter(batch.items, null, "unknown_error")
        }
    }

    private fun handleSuccess(
        batch: SelectedBatch,
        result: NetworkResult.Success<MemoryBatchResponse>,
    ): MemoryFlushReport {
        val response = result.data
        if (result.statusCode != HTTP_OK ||
            (response.requestId != null && response.requestId != batch.request.requestId)
        ) {
            return retryOrDeadLetter(batch.items, response.retryAfterSeconds, "invalid_success_response")
        }
        val accepted = response.accepted
        val duplicate = response.duplicate
        val rejected = response.rejected
        val errors = response.errors
        val countsMatch = accepted != null && accepted >= 0 && duplicate != null && duplicate >= 0 &&
            rejected != null && rejected >= 0 &&
            accepted + duplicate + rejected == batch.items.size
        if (!countsMatch || errors == null || errors.size != rejected) {
            return retryOrDeadLetter(batch.items, response.retryAfterSeconds, "response_count_mismatch")
        }
        val eventIndexById = batch.request.events.mapIndexed { index, event ->
            event.eventId to index
        }.toMap()
        val retryIndices = mutableSetOf<Int>()
        val permanentIndices = mutableSetOf<Int>()
        val seenIndices = mutableSetOf<Int>()
        errors.forEach { error ->
            val index = resolveErrorIndex(error, batch.request.events, eventIndexById)
                ?: return retryOrDeadLetter(batch.items, response.retryAfterSeconds, "invalid_error_index")
            if (!seenIndices.add(index)) {
                return retryOrDeadLetter(batch.items, response.retryAfterSeconds, "duplicate_error_index")
            }
            if (error.retryable) retryIndices += index else permanentIndices += index
        }
        val acknowledged = batch.items.filterIndexed { index, _ ->
            index !in retryIndices && index !in permanentIndices
        }
        val retryItems = retryIndices.map { batch.items[it] }
        val permanentItems = permanentIndices.map { batch.items[it] }
        store.acknowledge(acknowledged.map { it.record.event.eventId })
        if (permanentItems.isNotEmpty()) {
            store.moveToDeadLetter(permanentItems, "server_rejected")
        }
        val retryReport = retryOrDeadLetter(retryItems, response.retryAfterSeconds, "server_retryable")
        return MemoryFlushReport(
            batchesSent = 1,
            eventsAcknowledged = acknowledged.size,
            eventsRetried = retryReport.eventsRetried,
            eventsDeadLettered = permanentItems.size + retryReport.eventsDeadLettered,
        )
    }

    private fun resolveErrorIndex(
        error: MemoryBatchError,
        events: List<MemoryMetricEvent>,
        eventIndexById: Map<String, Int>,
    ): Int? {
        error.index?.let { index ->
            if (index !in events.indices) return null
            if (error.eventId != null && error.eventId != events[index].eventId) return null
            return index
        }
        return error.eventId?.let(eventIndexById::get)
    }

    private fun retryOrDeadLetter(
        items: List<MemoryQueueItem>,
        retryAfterSeconds: Long?,
        reason: String,
    ): MemoryFlushReport {
        if (items.isEmpty()) return MemoryFlushReport()
        val exhausted = items.filter { it.record.attempts + 1 >= config.maxAttempts }
        val retryItems = items - exhausted.toSet()
        if (exhausted.isNotEmpty()) store.moveToDeadLetter(exhausted, "retry_exhausted_$reason")
        if (retryItems.isEmpty()) {
            return MemoryFlushReport(batchesSent = 1, eventsDeadLettered = exhausted.size)
        }
        val delayMillis = retryAfterSeconds
            ?.coerceAtLeast(0L)
            ?.coerceAtMost(MAX_RETRY_DELAY_MILLIS / 1_000L)
            ?.times(1_000L)
            ?: calculateBackoffMillis(retryItems)
        store.markRetry(retryItems, clock() + delayMillis)
        logger { "memory batch retained for retry reason=$reason count=${retryItems.size}" }
        return MemoryFlushReport(
            batchesSent = 1,
            eventsRetried = retryItems.size,
            eventsDeadLettered = exhausted.size,
        )
    }

    private fun calculateBackoffMillis(items: List<MemoryQueueItem>): Long {
        val maxAttempts = items.maxOf { it.record.attempts }.coerceIn(0, MAX_BACKOFF_EXPONENT)
        val base = (BASE_RETRY_DELAY_MILLIS * (1L shl maxAttempts))
            .coerceAtMost(MAX_RETRY_DELAY_MILLIS)
        return min(MAX_RETRY_DELAY_MILLIS, base + random().toLong())
    }

    private operator fun MemoryFlushReport.plus(other: MemoryFlushReport): MemoryFlushReport {
        return MemoryFlushReport(
            batchesSent + other.batchesSent,
            eventsAcknowledged + other.eventsAcknowledged,
            eventsRetried + other.eventsRetried,
            eventsDeadLettered + other.eventsDeadLettered,
        )
    }

    private data class SelectedBatch(
        val request: MemoryBatchRequest,
        val items: List<MemoryQueueItem>,
    )

    private companion object {
        const val HTTP_OK = 200
        const val MAX_BATCHES_PER_FLUSH = 5
        const val BASE_RETRY_DELAY_MILLIS = 30_000L
        const val MAX_RETRY_DELAY_MILLIS = 60L * 60L * 1_000L
        const val MAX_BACKOFF_EXPONENT = 7
        const val JITTER_BOUND_MILLIS = 1_000
        const val MAX_EVENT_BYTES = 256 * 1024
    }
}
