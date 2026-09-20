package com.example.nativelib.crash

import com.example.nativelib.network.CrashEvent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileCrashQueueTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun queueWritesRecordsAtomicallyAndTracksRetryState() {
        val queue = FileCrashQueue(temporaryFolder.newFolder("queue"))
        val event = sampleEvent("event-1")

        assertTrue(queue.enqueue(event, createdAtMillis = 100L))
        assertFalse(queue.enqueue(event, createdAtMillis = 200L))
        assertEquals(1, queue.count())

        val item = queue.peekBatch(limit = 1, nowMillis = 100L).single()
        assertEquals(0, item.record.attempts)
        queue.markRetry(listOf(item), nextAttemptAtMillis = 10_000L)

        assertTrue(queue.peekBatch(limit = 1, nowMillis = 1_000L).isEmpty())
        val retried = queue.peekBatch(limit = 1, nowMillis = 10_000L).single()
        assertEquals(1, retried.record.attempts)

        queue.acknowledge(listOf(event.eventId))
        assertEquals(0, queue.count())
    }

    @Test
    fun deadLetterRemovesEventsWithoutLoggingPayload() {
        val root = temporaryFolder.newFolder("queue")
        val queue = FileCrashQueue(root)
        val event = sampleEvent("event-2")
        queue.enqueue(event)

        val item = queue.peekBatch(1).single()
        queue.moveToDeadLetter(listOf(item), "http_401")

        assertEquals(0, queue.count())
        val deadLetters = File(root, "dead-letter").listFiles().orEmpty()
        assertEquals(1, deadLetters.size)
        val deadLetterText = deadLetters.single().readText()
        assertTrue(deadLetterText.contains("http_401"))
        assertTrue(deadLetterText.contains("event-2"))
    }

    private fun sampleEvent(eventId: String): CrashEvent {
        return CrashEvent(
            schemaVersion = 2,
            eventId = eventId,
            eventType = "app_start",
            occurredAt = 1_726_000_000_000,
            sessionId = "session-1",
            processId = "11111111-1111-4111-8111-111111111111",
            anonymousDeviceId = "install-1",
            packageName = "com.example.performance",
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
