package com.example.nativelib.jank

import com.example.nativelib.network.JankArtifactUploadResponse
import com.example.nativelib.network.NetworkResult
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JankArtifactUploaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptedAndDuplicateResponsesDeleteArtifacts() = runBlocking {
        listOf("accepted", "duplicate").forEachIndexed { index, status ->
            val fixture = fixture("event-$index") {
                NetworkResult.Success(
                    JankArtifactUploadResponse(success = true, status = status),
                    statusCode = 200,
                )
            }

            val report = fixture.uploader.flush(maxArtifacts = 1)

            assertEquals(1, report.uploaded)
            assertEquals(1, report.deleted)
            assertEquals(0, fixture.queue.count())
            assertTrue(fixture.store.files.isEmpty())
        }
    }

    @Test
    fun retryAfterPreservesOriginalArtifactAndEventId() = runBlocking {
        var now = 10_000L
        val fixture = fixture("event-retry", clock = { now }) {
            NetworkResult.HttpError(
                statusCode = 503,
                responseBody = "{\"code\":\"STACK_PARSER_BUSY\"}",
                retryable = true,
                retryAfterSeconds = 60,
            )
        }

        val report = fixture.uploader.flush(maxArtifacts = 1)

        assertEquals(1, report.retried)
        assertNull(fixture.queue.nextReady(now + 59_999))
        val retryItem = fixture.queue.nextReady(now + 60_000)!!
        assertEquals("event-retry", retryItem.record.eventId)
        assertEquals("event-retry.rheajank.zip", retryItem.record.fileName)
        assertEquals(1, retryItem.record.attempts)
        assertTrue(fixture.artifact.isFile)
    }

    @Test
    fun permanentHttpFailureDeletesArtifactWithoutRetry() = runBlocking {
        val fixture = fixture("event-invalid") {
            NetworkResult.HttpError(
                statusCode = 422,
                responseBody = "{\"code\":\"INVALID_STACK_ARTIFACT\"}",
                retryable = false,
            )
        }

        val report = fixture.uploader.flush(maxArtifacts = 1)

        assertEquals(1, report.permanentlyRejected)
        assertEquals(1, report.deleted)
        assertEquals(0, fixture.queue.count())
        assertFalse(fixture.artifact.exists())
    }

    @Test
    fun malformedSuccessResponseIsRetried() = runBlocking {
        val fixture = fixture("event-ambiguous") {
            NetworkResult.Success(
                JankArtifactUploadResponse(success = true, status = "unexpected"),
                statusCode = 200,
            )
        }

        val report = fixture.uploader.flush(maxArtifacts = 1)

        assertEquals(1, report.retried)
        assertTrue(fixture.artifact.isFile)
        assertEquals(1, fixture.queue.find("event-ambiguous")?.record?.attempts)
    }

    @Test
    fun localValidationRejectsEmptyAndOversizedArtifacts() = runBlocking {
        val empty = fixture("event-empty", bytes = byteArrayOf()) {
            throw AssertionError("empty artifact must not be uploaded")
        }
        val emptyReport = empty.uploader.flush(maxArtifacts = 1)
        assertEquals(1, emptyReport.permanentlyRejected)
        assertFalse(empty.artifact.exists())

        val oversized = fixture(
            eventId = "event-large",
            bytes = byteArrayOf(1, 2, 3, 4),
            maxArtifactBytes = 3,
        ) {
            throw AssertionError("oversized artifact must not be uploaded")
        }
        val oversizedReport = oversized.uploader.flush(maxArtifacts = 1)
        assertEquals(1, oversizedReport.permanentlyRejected)
        assertFalse(oversized.artifact.exists())
    }

    @Test
    fun acknowledgedArtifactRetriesOnlyLocalDelete() = runBlocking {
        var now = 100_000L
        val uploads = AtomicInteger()
        val fixture = fixture(
            eventId = "event-delete",
            clock = { now },
            deleteResults = ArrayDeque(listOf(false, true)),
        ) {
            uploads.incrementAndGet()
            NetworkResult.Success(
                JankArtifactUploadResponse(success = true, status = "accepted"),
                statusCode = 200,
            )
        }

        val first = fixture.uploader.flush(maxArtifacts = 1)
        assertEquals(1, first.uploaded)
        assertEquals(1, first.retried)
        assertTrue(fixture.queue.find("event-delete")?.record?.deletePending == true)

        now += 30_000
        val second = fixture.uploader.flush(maxArtifacts = 1)

        assertEquals(1, second.deleted)
        assertEquals(1, uploads.get())
        assertEquals(0, fixture.queue.count())
    }

    @Test
    fun networkFailureUsesExponentialBackoff() = runBlocking {
        val fixture = fixture("event-network") {
            NetworkResult.NetworkError(IOException("offline"))
        }

        fixture.uploader.flush(maxArtifacts = 1)

        assertNull(fixture.queue.nextReady(29_999))
        assertEquals("event-network", fixture.queue.nextReady(30_000)?.record?.eventId)
    }

    private fun fixture(
        eventId: String,
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        maxArtifactBytes: Long = 64L * 1024L * 1024L,
        clock: () -> Long = { 0L },
        deleteResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true)),
        sender: suspend (File) -> NetworkResult<JankArtifactUploadResponse>,
    ): Fixture {
        val artifact = File(temporaryFolder.root, "$eventId.rheajank.zip").apply {
            writeBytes(bytes)
        }
        val queue = FileJankUploadQueue(temporaryFolder.newFolder("queue-$eventId"))
        val store = FakeArtifactStore(mutableListOf(artifact), deleteResults)
        queue.reconcile(store.pendingArtifacts(), clock())
        val uploader = JankArtifactUploader(
            queue = queue,
            store = store,
            sender = JankArtifactSender(sender),
            config = JankUploadConfig(
                maxArtifactBytes = maxArtifactBytes,
                uploadIntervalMillis = 30_000L,
                connectTimeoutMillis = 3_000L,
                readTimeoutMillis = 3_000L,
                writeTimeoutMillis = 3_000L,
                enableNetworkLogging = false,
            ),
            clock = clock,
        )
        return Fixture(artifact, queue, store, uploader)
    }

    private data class Fixture(
        val artifact: File,
        val queue: FileJankUploadQueue,
        val store: FakeArtifactStore,
        val uploader: JankArtifactUploader,
    )

    private class FakeArtifactStore(
        val files: MutableList<File>,
        private val deleteResults: ArrayDeque<Boolean>,
    ) : JankArtifactStore {
        override fun pendingArtifacts(): List<File> = files.toList()

        override fun delete(artifact: File): Boolean {
            val result = deleteResults.removeFirstOrNull() ?: true
            if (result) {
                files.remove(artifact)
                artifact.delete()
            }
            return result
        }
    }
}
