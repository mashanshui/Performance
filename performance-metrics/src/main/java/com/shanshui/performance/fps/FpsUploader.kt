package com.shanshui.performance.fps

import com.shanshui.performance.metrics.FpsConfig
import com.shanshui.performance.network.FpsBatchError
import com.shanshui.performance.network.FpsBatchRequest
import com.shanshui.performance.network.FpsBatchResponse
import com.shanshui.performance.network.FpsNetworkClient
import com.shanshui.performance.network.NetworkResult
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 网络发送边界，测试可用内存 fake 替换真实 Retrofit 客户端。 */
internal fun interface FpsBatchSender {
    /** 发送 FPS 批次并返回协议层结果。 */
    suspend fun send(request: FpsBatchRequest): NetworkResult<FpsBatchResponse>
}

/** 一次 FPS 刷新循环的可观测结果，不包含事件正文。 */
internal data class FpsFlushReport(
    val batchesSent: Int = 0,
    val eventsAcknowledged: Int = 0,
    val eventsRetried: Int = 0,
    val eventsDeadLettered: Int = 0,
)

/**
 * FPS 事件批次选择、响应校验和重试调度器。
 *
 * 只有服务端 HTTP 200 且 accepted/duplicate/rejected 与事件级错误能互相印证时，
 * 才会从本地队列删除事件；任何不完整响应都保留原 eventId 重试。
 */
internal class FpsUploader(
    private val store: FpsEventStore,
    private val sender: FpsBatchSender,
    private val config: FpsConfig,
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val requestIdGenerator: () -> String = { UUID.randomUUID().toString() },
    private val logger: (() -> String) -> Unit = {},
) {
    /** 尝试发送到期队列，最多处理指定批次数量。 */
    suspend fun flush(maxBatches: Int = MAX_BATCHES_PER_FLUSH): FpsFlushReport {
        require(maxBatches > 0) { "maxBatches must be positive" }
        store.expireOlderThan(clock())
        var report = FpsFlushReport()
        repeat(maxBatches) {
            val selected = selectBatch(clock()) ?: return report
            val result = sender.send(selected.request)
            val update = handleResult(selected, result)
            report += update
            if (update.eventsRetried > 0 || result !is NetworkResult.Success) {
                return report
            }
        }
        return report
    }

    /** 从队列中选择批次并按 UTF-8 JSON 大小上限截断。 */
    private fun selectBatch(nowMillis: Long): SelectedBatch? {
        val candidates = store.peekBatch(config.batchSize, nowMillis)
        if (candidates.isEmpty()) {
            return null
        }
        val requestId = requestIdGenerator()
        val selected = mutableListOf<FpsQueueItem>()
        for (candidate in candidates) {
            val tentative = FpsBatchRequest(
                requestId = requestId,
                events = (selected + candidate).map { it.record.event },
            )
            val size = gson.toJson(tentative).toByteArray(StandardCharsets.UTF_8).size
            if (size > config.maxBatchBytes) {
                if (selected.isEmpty()) {
                    store.moveToDeadLetter(listOf(candidate), "event_too_large")
                    logger { "fps event isolated because batch size exceeded limit" }
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
            request = FpsBatchRequest(requestId, selected.map { it.record.event }),
            items = selected,
        )
    }

    /** 根据网络结果选择确认、永久隔离或保留重试。 */
    private fun handleResult(
        batch: SelectedBatch,
        result: NetworkResult<FpsBatchResponse>,
    ): FpsFlushReport {
        return when (result) {
            is NetworkResult.Success -> handleSuccess(batch, result)
            is NetworkResult.HttpError -> {
                if (result.retryable) {
                    retry(batch.items, result.retryAfterSeconds, "http_${result.statusCode}")
                } else {
                    store.moveToDeadLetter(batch.items, "http_${result.statusCode}")
                    FpsFlushReport(
                        batchesSent = 1,
                        eventsDeadLettered = batch.items.size,
                    )
                }
            }
            is NetworkResult.NetworkError -> retry(
                batch.items,
                null,
                "network_error:${result.exception.javaClass.simpleName}",
            )
            is NetworkResult.SerializationError -> retry(
                batch.items,
                null,
                "serialization_error:${result.exception.javaClass.simpleName}",
            )
            is NetworkResult.UnknownError -> retry(
                batch.items,
                null,
                "unknown_error:${result.exception.javaClass.simpleName}",
            )
        }
    }

    /** 校验 HTTP 200 响应和事件错误索引，再更新本地队列状态。 */
    private fun handleSuccess(
        batch: SelectedBatch,
        result: NetworkResult.Success<FpsBatchResponse>,
    ): FpsFlushReport {
        val response = result.data
        if (result.statusCode != HTTP_OK ||
            (response.requestId != null && response.requestId != batch.request.requestId)
        ) {
            return retry(batch.items, response.retryAfterSeconds, "invalid_success_response")
        }
        val countsMatch = response.accepted >= 0 &&
            response.duplicate >= 0 &&
            response.rejected >= 0 &&
            response.accepted + response.duplicate + response.rejected == batch.items.size
        if (!countsMatch) {
            logger { "fps response count mismatch; preserving batch for retry" }
            return retry(batch.items, response.retryAfterSeconds, "response_count_mismatch")
        }

        val eventIndexById = batch.request.events
            .mapIndexed { index, event -> event.eventId to index }
            .toMap()
        val retryIndices = mutableSetOf<Int>()
        val permanentIndices = mutableSetOf<Int>()
        val seenErrorIndices = mutableSetOf<Int>()
        for (error in response.errors) {
            val index = resolveErrorIndex(error, batch.request.events, eventIndexById)
                ?: return retry(batch.items, response.retryAfterSeconds, "invalid_response_error_index")
            if (!seenErrorIndices.add(index)) {
                return retry(batch.items, response.retryAfterSeconds, "duplicate_response_error_index")
            }
            if (error.retryable) {
                retryIndices += index
            } else {
                permanentIndices += index
            }
        }
        if (response.errors.size != response.rejected) {
            logger { "fps response error count mismatch; preserving batch for retry" }
            return retry(batch.items, response.retryAfterSeconds, "response_error_count_mismatch")
        }

        val acknowledged = batch.items.filterIndexed { index, _ ->
            index !in retryIndices && index !in permanentIndices
        }
        val retryItems = retryIndices
            .filter { it !in permanentIndices }
            .map { batch.items[it] }
        val permanentItems = permanentIndices.map { batch.items[it] }
        if (permanentItems.isNotEmpty()) {
            val codes = response.errors.filter { !it.retryable }
                .mapNotNull { it.code }
                .distinct()
                .joinToString(",")
                .ifBlank { "permanent_event_error" }
            store.moveToDeadLetter(permanentItems, "server_rejected:$codes")
            logger { "fps permanent event errors isolated count=${permanentItems.size}" }
        }
        store.acknowledge(acknowledged.map { it.record.event.eventId })
        if (retryItems.isNotEmpty()) {
            val retryReport = retry(
                retryItems,
                response.retryAfterSeconds,
                "server_event_retryable",
            )
            return FpsFlushReport(
                batchesSent = 1,
                eventsAcknowledged = acknowledged.size,
                eventsRetried = retryReport.eventsRetried,
                eventsDeadLettered = permanentItems.size,
            )
        }
        logger {
            "fps batch acknowledged accepted=${response.accepted} " +
                "duplicate=${response.duplicate}"
        }
        return FpsFlushReport(
            batchesSent = 1,
            eventsAcknowledged = acknowledged.size,
            eventsDeadLettered = permanentItems.size,
        )
    }

    /** 解析服务端错误定位，要求 index 和 eventId 同时出现时必须一致。 */
    private fun resolveErrorIndex(
        error: FpsBatchError,
        events: List<com.shanshui.performance.network.FpsMetricEvent>,
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

    /** 为网络错误和事件级可重试错误安排指数退避。 */
    private fun retry(
        items: List<FpsQueueItem>,
        retryAfterSeconds: Long?,
        reason: String,
    ): FpsFlushReport {
        if (items.isEmpty()) {
            return FpsFlushReport()
        }
        val delayMillis = retryAfterSeconds
            ?.coerceAtLeast(0L)
            ?.coerceAtMost(MAX_RETRY_DELAY_MILLIS / 1_000L)
            ?.times(1_000L)
            ?: calculateBackoffMillis(items)
        store.markRetry(items, clock() + delayMillis)
        logger { "fps batch retained for retry reason=$reason count=${items.size}" }
        return FpsFlushReport(eventsRetried = items.size)
    }

    /** 根据本批最大尝试次数计算 30 秒起步、最多 1 小时的退避。 */
    private fun calculateBackoffMillis(items: List<FpsQueueItem>): Long {
        val maxAttempts = items.maxOf { it.record.attempts }.coerceIn(0, MAX_BACKOFF_EXPONENT)
        return (BASE_RETRY_DELAY_MILLIS * (1L shl maxAttempts))
            .coerceAtMost(MAX_RETRY_DELAY_MILLIS)
    }

    /** 合并两次刷新报告，便于调用方输出单轮结果。 */
    private operator fun FpsFlushReport.plus(other: FpsFlushReport): FpsFlushReport {
        return FpsFlushReport(
            batchesSent = batchesSent + other.batchesSent,
            eventsAcknowledged = eventsAcknowledged + other.eventsAcknowledged,
            eventsRetried = eventsRetried + other.eventsRetried,
            eventsDeadLettered = eventsDeadLettered + other.eventsDeadLettered,
        )
    }

    /** 已选中的请求和其本地队列项。 */
    private data class SelectedBatch(
        val request: FpsBatchRequest,
        val items: List<FpsQueueItem>,
    )

    private companion object {
        const val HTTP_OK = 200
        const val MAX_BATCHES_PER_FLUSH = 5
        const val BASE_RETRY_DELAY_MILLIS = 30_000L
        const val MAX_RETRY_DELAY_MILLIS = 60L * 60L * 1_000L
        const val MAX_BACKOFF_EXPONENT = 7
    }
}

/** 让生产 reporter 能方便地绑定真实网络客户端。 */
internal fun FpsNetworkClient.asFpsBatchSender(): FpsBatchSender {
    return FpsBatchSender { request -> sendBatch(request) }
}
