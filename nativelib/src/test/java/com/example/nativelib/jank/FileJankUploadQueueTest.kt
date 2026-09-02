package com.example.nativelib.jank

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileJankUploadQueueTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun retryAndDeletePendingStateSurviveQueueRecreation() {
        val root = temporaryFolder.newFolder("queue")
        val artifact = artifact("event-1")
        val queue = FileJankUploadQueue(root)
        queue.reconcile(listOf(artifact), nowMillis = 1_000)
        val first = queue.nextReady(1_000)!!

        queue.markRetry(first, nextAttemptAtMillis = 31_000)

        val restored = FileJankUploadQueue(root)
        assertNull(restored.nextReady(30_999))
        val retried = restored.nextReady(31_000)!!
        assertEquals("event-1", retried.record.eventId)
        assertEquals(1, retried.record.attempts)
        assertEquals("event-1.rheajank.zip", retried.record.fileName)

        restored.markDeletePending(retried)

        val deletePending = FileJankUploadQueue(root).nextReady(0)!!
        assertTrue(deletePending.record.deletePending)
        assertEquals(1, deletePending.record.attempts)
    }

    @Test
    fun reconcileRestoresPendingArtifactsAndRemovesMissingMetadata() {
        val root = temporaryFolder.newFolder("queue")
        val firstArtifact = artifact("event-a")
        val secondArtifact = artifact("event-b")
        val queue = FileJankUploadQueue(root)
        queue.reconcile(listOf(firstArtifact, secondArtifact), nowMillis = 2_000)
        assertEquals(2, queue.count())

        queue.reconcile(listOf(secondArtifact), nowMillis = 3_000)

        assertEquals(1, queue.count())
        assertEquals("event-b", queue.nextReady(3_000)?.record?.eventId)
        assertFalse(queue.enqueue(secondArtifact))
    }

    private fun artifact(eventId: String): File {
        return File(temporaryFolder.root, "$eventId.rheajank.zip").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
    }
}
