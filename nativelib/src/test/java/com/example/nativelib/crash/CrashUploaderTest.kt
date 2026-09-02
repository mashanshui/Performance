package com.example.nativelib.crash

import com.example.nativelib.config.NativeServiceConfig
import com.example.nativelib.network.BatchError
import com.example.nativelib.network.CrashBatchResponse
import com.example.nativelib.network.CrashEvent
import com.example.nativelib.network.NetworkResult
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashUploaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun partialSuccessAcknowledgesAcceptedAndDeadLettersPermanentEvent() = runBlocking {
        val queue = FileCrashQueue(temporaryFolder.newFolder("queue"))
        queue.enqueue(sampleEvent("event-1"))
        queue.enqueue(sampleEvent("event-2"))
        val sent = AtomicReference<com.example.nativelib.network.CrashBatchRequest>()
        val uploader = uploader(queue) { request ->
            sent.set(request)
            NetworkResult.Success(
                CrashBatchResponse(
                    accepted = 1,
                    rejected = 1,
                    errors = listOf(
                        BatchError(index = 1, eventId = "event-2", code = "INVALID_BATCH"),
                    ),
                ),
                statusCode = 200,
            )
        }

        val report = uploader.flush(maxBatches = 1)

        assertEquals(2, sent.get().events.size)
        assertEquals(1, report.eventsAcknowledged)
        assertEquals(1, report.eventsDeadLettered)
        assertEquals(0, queue.count())
    }

    @Test
    fun retryableHttpErrorKeepsOriginalEventIdAndSchedulesRetry() = runBlocking {
        val queue = FileCrashQueue(temporaryFolder.newFolder("queue"))
        queue.enqueue(sampleEvent("event-3"))
        val uploader = uploader(queue) {
            NetworkResult.HttpError(
                statusCode = 503,
                responseBody = null,
                retryable = true,
                retryAfterSeconds = 60,
            )
        }

        val report = uploader.flush(maxBatches = 1)
        val item = queue.peekBatch(1, nowMillis = 1_726_000_000_000).singleOrNull()

        assertEquals(1, report.eventsRetried)
        assertEquals(1, queue.count())
        assertTrue(item == null)
        assertEquals("event-3", queue.peekBatch(1, Long.MAX_VALUE).single().record.event.eventId)
        assertEquals(1, queue.peekBatch(1, Long.MAX_VALUE).single().record.attempts)
    }

    @Test
    fun invalidSuccessResponseIsRetriedInsteadOfAcknowledged() = runBlocking {
        val queue = FileCrashQueue(temporaryFolder.newFolder("queue"))
        queue.enqueue(sampleEvent("event-4"))
        val uploader = uploader(queue) {
            NetworkResult.Success(
                CrashBatchResponse(
                    accepted = 1,
                    errors = listOf(BatchError(index = 9, retryable = false)),
                ),
                statusCode = 200,
            )
        }

        val report = uploader.flush(maxBatches = 1)

        assertEquals(1, report.eventsRetried)
        assertEquals(1, queue.count())
        assertEquals(1, queue.peekBatch(1, Long.MAX_VALUE).single().record.attempts)
    }

    private fun uploader(
        queue: FileCrashQueue,
        sender: CrashBatchSender,
    ): CrashUploader {
        return CrashUploader(
            queue = queue,
            sender = sender,
            config = NativeServiceConfig(
                baseUrl = "https://example.test/",
                appKey = "test-app-key",
                environment = "test",
                channel = "unit-test",
                schemaVersion = 1,
                enableNetworkLogging = false,
                connectTimeoutMillis = 3_000L,
                readTimeoutMillis = 3_000L,
                writeTimeoutMillis = 3_000L,
                crashEnabled = true,
                crashBatchSize = 20,
                crashMaxBatchBytes = 512 * 1024,
                crashUploadIntervalMillis = 30_000L,
                jankEnabled = true,
                jankUploadIntervalMillis = 30_000L,
                jankMaxArtifactBytes = 64L * 1024L * 1024L,
            ),
            clock = { 1_726_000_000_000 },
            requestIdGenerator = { "request-1" },
        )
    }

    private fun sampleEvent(eventId: String): CrashEvent {
        return CrashEvent(
            schemaVersion = 1,
            eventId = eventId,
            eventType = "crash",
            occurredAt = 1_726_000_000_000,
            sessionId = "session-1",
            anonymousDeviceId = "install-1",
            appId = "demo-app",
            appVersion = "1.0",
            versionCode = 1,
            buildId = "build-1",
            environment = "test",
            channel = "unit-test",
            osVersion = "35",
            deviceModel = "Pixel Test",
        )
    }
}
