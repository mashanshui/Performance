package com.example.nativelib.memory.leak

import com.example.nativelib.network.MemoryLeakReportMetadata
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryLeakReportStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun enqueueUsesAtomicFilesAndRestoresAfterCreatingANewStore() {
        val root = temporaryFolder.newFolder("queue")
        val metadata = sampleMetadata("event-1")
        val report = "{\"runningInfo\":{},\"gcPaths\":[],\"classInfos\":[],\"leakObjects\":[]}"

        assertTrue(MemoryLeakReportStore(root).enqueue(metadata, report, createdAtMillis = 10L))

        val restored = MemoryLeakReportStore(root).nextReady(nowMillis = 10L)
        assertNotNull(restored)
        assertEquals("event-1", restored!!.record.metadata.eventId)
        assertEquals(report, restored.reportFile.readText())
        assertEquals(1, MemoryLeakReportStore(root).count())
    }

    @Test
    fun retryStateAndStableEventIdSurviveStoreRecreation() {
        val root = temporaryFolder.newFolder("retry")
        val metadata = sampleMetadata("event-2")
        val store = MemoryLeakReportStore(root)
        store.enqueue(metadata, "{}", createdAtMillis = 100L)
        val item = store.nextReady(nowMillis = 100L)!!

        store.markRetry(item, nextAttemptAtMillis = 5_000L)

        val restoredStore = MemoryLeakReportStore(root)
        assertFalse(restoredStore.nextReady(nowMillis = 4_999L) != null)
        val restored = restoredStore.nextReady(nowMillis = 5_000L)
        assertNotNull(restored)
        assertEquals("event-2", restored!!.record.metadata.eventId)
        assertEquals(1, restored.record.attempts)
        assertEquals(5_000L, restored.record.nextAttemptAtMillis)
    }

    @Test
    fun expiredReportsAreRemovedWithoutAffectingNewerReports() {
        val root = temporaryFolder.newFolder("ttl")
        val store = MemoryLeakReportStore(root)
        store.enqueue(sampleMetadata("old-event"), "{}", createdAtMillis = 1L)
        store.enqueue(sampleMetadata("new-event"), "{}", createdAtMillis = 100L)

        store.expireOlderThan(cutoffMillis = 50L)

        assertEquals(1, store.count())
        assertEquals("new-event", store.nextReady(nowMillis = 100L)!!.record.metadata.eventId)
    }

    @Test
    fun expiredDeadLetterReportsAreRemovedByTheSameRetentionBoundary() {
        val root = temporaryFolder.newFolder("dead-letter-ttl")
        val store = MemoryLeakReportStore(root)
        store.enqueue(sampleMetadata("expired-event"), "{}", createdAtMillis = 1L)
        val item = store.nextReady(nowMillis = 1L)!!
        store.moveToDeadLetter(item, "permanent")

        store.expireOlderThan(cutoffMillis = 50L)

        assertTrue(File(root, "dead-letter").listFiles { file -> file.isDirectory }.orEmpty().isEmpty())
    }

    @Test
    fun reportOverSizeLimitIsRejectedBeforeCreatingAnEvent() {
        val root = temporaryFolder.newFolder("size")
        val store = MemoryLeakReportStore(root)
        val oversizedReport = "x".repeat((MemoryLeakReportLimits.MAX_REPORT_BYTES + 1L).toInt())

        assertFalse(store.enqueue(sampleMetadata("large-event"), oversizedReport))
        assertEquals(0, store.count())
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
            processId = "11111111-1111-4111-8111-111111111111",
            buildId = "build-1",
            environment = "debug",
            channel = "official",
        )
    }
}
