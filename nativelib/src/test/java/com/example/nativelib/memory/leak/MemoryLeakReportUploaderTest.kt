package com.example.nativelib.memory.leak

import com.example.nativelib.network.MemoryLeakReportMetadata
import com.example.nativelib.network.MemoryLeakReportUploadResponse
import com.example.nativelib.network.NetworkResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryLeakReportUploaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptedResponseAcknowledgesAndDeletesQueuedReport() = runBlocking {
        val root = temporaryFolder.newFolder("accepted")
        val store = MemoryLeakReportStore(root)
        val metadata = sampleMetadata("accepted-event")
        store.enqueue(metadata, "report-content", createdAtMillis = 0L)
        var sentContent: String? = null
        val uploader = MemoryLeakReportUploader(
            store = store,
            sender = MemoryLeakReportSender { sentMetadata, reportFile ->
                assertEquals(metadata.eventId, sentMetadata.eventId)
                sentContent = reportFile.readText()
                NetworkResult.Success(
                    MemoryLeakReportUploadResponse(
                        eventId = metadata.eventId,
                        status = "accepted",
                        issueCount = 1,
                        attachmentStatus = "absent",
                    ),
                    statusCode = 200,
                )
            },
            clock = { 0L },
        )

        val result = uploader.flush(maxReports = 1)

        assertEquals(1, result.reportsAcknowledged)
        assertEquals(0, store.count())
        assertEquals("report-content", sentContent)
    }

    @Test
    fun duplicateResponseAcknowledgesReport() = runBlocking {
        val root = temporaryFolder.newFolder("duplicate")
        val store = MemoryLeakReportStore(root)
        val metadata = sampleMetadata("duplicate-event")
        store.enqueue(metadata, "report-content", createdAtMillis = 0L)
        val uploader = MemoryLeakReportUploader(
            store = store,
            sender = MemoryLeakReportSender { _, _ ->
                NetworkResult.Success(
                    MemoryLeakReportUploadResponse(
                        eventId = metadata.eventId,
                        status = "duplicate",
                        issueCount = 1,
                        attachmentStatus = "absent",
                    ),
                    statusCode = 200,
                )
            },
            clock = { 0L },
        )

        val result = uploader.flush(maxReports = 1)

        assertEquals(1, result.reportsAcknowledged)
        assertEquals(0, store.count())
    }

    @Test
    fun transientFailureRetainsStableEventIdAndSchedulesRetry() = runBlocking {
        val root = temporaryFolder.newFolder("retry")
        val store = MemoryLeakReportStore(root)
        val metadata = sampleMetadata("retry-event")
        store.enqueue(metadata, "report-content", createdAtMillis = 0L)
        val uploader = MemoryLeakReportUploader(
            store = store,
            sender = MemoryLeakReportSender { _, _ ->
                NetworkResult.HttpError(
                    statusCode = 503,
                    responseBody = null,
                    retryable = true,
                    retryAfterSeconds = 42L,
                )
            },
            clock = { 0L },
        )

        val result = uploader.flush(maxReports = 1)
        val retained = MemoryLeakReportStore(root).nextReady(nowMillis = 42_000L)

        assertEquals(1, result.reportsRetried)
        assertTrue(retained != null)
        assertEquals(metadata.eventId, retained!!.record.metadata.eventId)
        assertEquals(1, retained.record.attempts)
        assertEquals(42_000L, retained.record.nextAttemptAtMillis)
    }

    @Test
    fun permanentFailureMovesReportToDeadLetter() = runBlocking {
        val root = temporaryFolder.newFolder("permanent")
        val store = MemoryLeakReportStore(root)
        val metadata = sampleMetadata("permanent-event")
        store.enqueue(metadata, "report-content", createdAtMillis = 0L)
        val uploader = MemoryLeakReportUploader(
            store = store,
            sender = MemoryLeakReportSender { _, _ ->
                NetworkResult.HttpError(
                    statusCode = 415,
                    responseBody = null,
                    retryable = false,
                )
            },
            clock = { 0L },
        )

        val result = uploader.flush(maxReports = 1)

        assertEquals(1, result.reportsDeadLettered)
        assertEquals(0, store.count())
        val deadLetterDirectory = File(root, "dead-letter")
        assertTrue(deadLetterDirectory.listFiles { file -> file.isDirectory }.orEmpty().isNotEmpty())
    }

    private fun sampleMetadata(eventId: String): MemoryLeakReportMetadata {
        return MemoryLeakReportMetadata(
            schemaVersion = 1,
            eventId = eventId,
            occurredAt = 1_780_000_000_000,
            packageName = "com.example.memoryleak",
            appVersion = "1.0.0",
            versionCode = 1,
            anonymousDeviceId = "device-hash",
            processName = "com.example.memoryleak",
            sessionId = "session-1",
            buildId = "build-1",
            environment = "debug",
            channel = "official",
        )
    }
}
