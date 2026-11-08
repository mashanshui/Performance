package com.shanshui.performance.memory

import com.shanshui.performance.metrics.MemoryConfig
import com.shanshui.performance.network.MemoryBatchError
import com.shanshui.performance.network.MemoryBatchResponse
import com.shanshui.performance.network.MemoryMetricEvent
import com.shanshui.performance.network.MemoryPeakSummary
import com.shanshui.performance.network.NetworkResult
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内存事件批量确认、稳定 ID 和不完整响应保留测试。 */
class MemoryUploaderTest {
    @Test
    fun acceptedAndDuplicateResponsesDeleteEvents() = runBlocking {
        val root = Files.createTempDirectory("memory-uploader-test")
        try {
            val store = createStore(root)
            val event = event("event-1")
            store.enqueue(event)
            /** 确认确实发生发送，不能让过期删除伪装成 duplicate 确认。 */
            var sends = 0
            val uploader = MemoryUploader(
                store = store,
                sender = MemoryBatchSender {
                    sends++
                    NetworkResult.Success(
                        MemoryBatchResponse(
                            requestId = it.requestId,
                            duplicate = 1,
                        ),
                        200,
                    )
                },
                config = MemoryConfig(),
                clock = { 1_000L },
            )

            uploader.flush()

            assertEquals(0, store.count())
            assertEquals(1, sends)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun networkRetryKeepsOriginalEventId() = runBlocking {
        val root = Files.createTempDirectory("memory-retry-test")
        try {
            val store = createStore(root)
            store.enqueue(event("event-2"), createdAtMillis = 1_000L)
            val sentIds = mutableListOf<String>()
            var attempt = 0
            var clockCalls = 0
            val uploader = MemoryUploader(
                store = store,
                sender = MemoryBatchSender {
                    sentIds += it.events.single().eventId
                    attempt += 1
                    if (attempt == 1) {
                        NetworkResult.NetworkError(IOException("offline"))
                    } else {
                        NetworkResult.Success(
                            MemoryBatchResponse(requestId = it.requestId, accepted = 1),
                            200,
                        )
                    }
                },
                config = MemoryConfig(),
                clock = {
                    clockCalls += 1
                    if (clockCalls <= 3) 1_000L else 100_000L
                },
                random = { 0 },
            )

            uploader.flush()
            uploader.flush()

            assertEquals(listOf(event("event-2").eventId, event("event-2").eventId), sentIds)
            assertEquals(0, store.count())
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun inconsistentResponseKeepsEventForRetry() = runBlocking {
        val root = Files.createTempDirectory("memory-invalid-response-test")
        try {
            val store = createStore(root)
            store.enqueue(event("event-3"))
            val uploader = MemoryUploader(
                store = store,
                sender = MemoryBatchSender {
                    NetworkResult.Success(MemoryBatchResponse(requestId = it.requestId), 200)
                },
                config = MemoryConfig(),
                clock = { 1_000L },
                random = { 0 },
            )

            val report = uploader.flush()

            assertEquals(1, store.count())
            assertEquals(1, report.eventsRetried)
            assertTrue(store.peekBatch(10, nowMillis = 1_000L).isEmpty())
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun nullResponseFieldsAreTreatedAsIncomplete() = runBlocking {
        val root = Files.createTempDirectory("memory-null-response-test")
        try {
            val store = createStore(root)
            store.enqueue(event("event-null-response"))
            val uploader = MemoryUploader(
                store = store,
                sender = MemoryBatchSender {
                    NetworkResult.Success(
                        MemoryBatchResponse(
                            requestId = it.requestId,
                            accepted = null,
                            errors = null,
                        ),
                        200,
                    )
                },
                config = MemoryConfig(),
                clock = { 1_000L },
                random = { 0 },
            )

            val report = uploader.flush()

            assertEquals(1, store.count())
            assertEquals(1, report.eventsRetried)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun serviceUnavailableHonorsRetryAfterAndKeepsEvent() = runBlocking {
        val root = Files.createTempDirectory("memory-503-test")
        try {
            val store = createStore(root)
            store.enqueue(event("event-4"), createdAtMillis = 1_000L)
            val uploader = MemoryUploader(
                store = store,
                sender = MemoryBatchSender {
                    NetworkResult.HttpError(
                        statusCode = 503,
                        responseBody = null,
                        retryable = true,
                        retryAfterSeconds = 30L,
                    )
                },
                config = MemoryConfig(),
                clock = { 1_000L },
            )

            uploader.flush()

            assertEquals(1, store.count())
            assertTrue(store.peekBatch(10, nowMillis = 30_999L).isEmpty())
            assertEquals(1, store.peekBatch(10, nowMillis = 31_000L).size)
            assertEquals(1, store.peekBatch(10, nowMillis = 31_000L)
                .single().record.attempts)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun mixedAcceptedRetryableAndPackageErrorsAreSeparated() = runBlocking {
        val root = Files.createTempDirectory("memory-mixed-response-test")
        try {
            val store = createStore(root)
            store.enqueue(event("event-accepted"))
            store.enqueue(event("event-retryable"))
            store.enqueue(event("event-package-error"))
            val uploader = MemoryUploader(
                store = store,
                sender = MemoryBatchSender {
                    NetworkResult.Success(
                        MemoryBatchResponse(
                            requestId = it.requestId,
                            accepted = 1,
                            rejected = 2,
                            errors = listOf(
                                MemoryBatchError(eventId = event("event-retryable").eventId, code = "TEMPORARY", retryable = true),
                                MemoryBatchError(
                                    eventId = event("event-package-error").eventId,
                                    code = "PACKAGE_NAME_MISMATCH",
                                    retryable = false,
                                ),
                            ),
                        ),
                        200,
                    )
                },
                config = MemoryConfig(),
                clock = { 1_000L },
                random = { 0 },
            )

            val report = uploader.flush()

            assertEquals(1, report.eventsAcknowledged)
            assertEquals(1, report.eventsRetried)
            assertEquals(1, report.eventsDeadLettered)
            assertEquals(1, store.count())
            assertEquals(event("event-retryable").eventId, store.peekBatch(10, 100_000).single().record.event.eventId)
            assertEquals(1, root.resolve("dead-letter").toFile().listFiles().orEmpty().size)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun retryExhaustionMovesEventToDeadLetter() = runBlocking {
        val root = Files.createTempDirectory("memory-exhausted-test")
        try {
            val store = MemoryEventStore(
                root = root.toFile(),
                diskQuotaBytes = 2L * 1024L * 1024L,
                eventTtlMillis = 7L * 24L * 60L * 60L * 1_000L,
                maxAttempts = 1,
            )
            store.enqueue(event("event-exhausted"))
            val uploader = MemoryUploader(
                store = store,
                sender = MemoryBatchSender { NetworkResult.NetworkError(IOException("timeout")) },
                config = MemoryConfig(maxAttempts = 1),
                clock = { 1_000L },
            )

            val report = uploader.flush()

            assertEquals(0, store.count())
            assertEquals(1, report.eventsDeadLettered)
            assertEquals(1, root.resolve("dead-letter").toFile().listFiles().orEmpty().size)
        } finally {
            deleteRecursively(root)
        }
    }

    private fun createStore(root: java.nio.file.Path): MemoryEventStore {
        return MemoryEventStore(
            root = root.toFile(),
            diskQuotaBytes = 2L * 1024L * 1024L,
            eventTtlMillis = 7L * 24L * 60L * 60L * 1_000L,
            maxAttempts = 10,
        )
    }

    /** 由测试名称生成稳定合法 UUID，构造仅含运行峰值的新协议事件。 */
    private fun event(eventId: String): MemoryMetricEvent {
        // 测试名称稳定映射到合法 UUID v4，仍可断言重试身份不变。
        val uuid = java.util.UUID.nameUUIDFromBytes(eventId.toByteArray()).toString().replaceRange(14, 15, "4")
        return MemoryMetricEvent(
            schemaVersion = 2,
            eventId = uuid,
            eventType = "memory_peak_summary",
            occurredAt = 1_000L,
            sessionId = "11111111-1111-4111-8111-111111111111",
            processId = "11111111-1111-4111-8111-111111111111",
            anonymousDeviceId = "device",
            packageName = "com.shanshui.test",
            appVersion = "1.0",
            versionCode = 1,
            buildId = "build",
            environment = "test",
            channel = "unit",
            osVersion = "35",
            deviceModel = "test",
            memoryPeakSummary = MemoryPeakSummary(
                processName = "com.shanshui.test", collectionStartedAt = 1_000L, lastSampledAt = 1_000L,
                pss = com.shanshui.performance.network.MemoryPeak(1L, 1_000L, true),
                javaHeap = com.shanshui.performance.network.MemoryPeak(2L, 1_000L, true),
            ),
        )
    }

    private fun deleteRecursively(root: java.nio.file.Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
