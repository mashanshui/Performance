package com.example.nativelib.memory.leak

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用普通对象验证泄漏 tracker，避免 JVM 单测依赖 Android 的真实 GC 时机。 */
class ActivityLeakTrackerTest {
    @Test
    fun collectedWeakReferenceIsRemovedWithoutCallback() {
        val callbacks = AtomicInteger()
        val tracker = ActivityLeakTracker<Any>(maxRecheckCount = 2) { callbacks.incrementAndGet() }
        val reference = WeakReference(Any())
        assertTrue(tracker.enqueue(reference, nowMillis = 0L, delayMillis = 0L))
        reference.clear()

        val result = tracker.scan(
            nowMillis = 0L,
            nextCheckDelayMillis = 10L,
            debuggerConnected = false,
            gcAndFinalize = {},
        )

        assertEquals(0, result.checkedCount)
        assertEquals(1, result.collectedCount)
        assertEquals(0, callbacks.get())
        assertEquals(0, tracker.size)
    }

    @Test
    fun liveObjectIsRecheckedAndReportedAtMaximumCount() {
        val callbacks = AtomicInteger()
        val tracker = ActivityLeakTracker<Any>(maxRecheckCount = 2) { callbacks.incrementAndGet() }
        val retainedObject = Any()
        val reference = WeakReference(retainedObject)
        assertTrue(tracker.enqueue(reference, nowMillis = 0L, delayMillis = 0L))
        assertTrue(reference.get() === retainedObject)

        val first = tracker.scan(0L, 10L, debuggerConnected = false, gcAndFinalize = {})
        assertEquals(1, first.checkedCount)
        assertEquals(0, first.leakedCount)
        assertEquals(0, callbacks.get())
        assertEquals(1, tracker.size)
        assertEquals(10L, tracker.nextDueAtMillis())

        val second = tracker.scan(10L, 10L, debuggerConnected = false, gcAndFinalize = {})
        assertEquals(1, second.checkedCount)
        assertEquals(1, second.leakedCount)
        assertEquals(1, callbacks.get())
        assertEquals(0, tracker.size)

        // 候选已在回调前移除，后续扫描不会重复通知。
        val third = tracker.scan(20L, 10L, debuggerConnected = false, gcAndFinalize = {})
        assertEquals(0, third.checkedCount)
        assertEquals(1, callbacks.get())
    }

    @Test
    fun debuggerSkipDoesNotRunGcOrCallbackAndDefersCandidate() {
        val callbacks = AtomicInteger()
        val gcCalls = AtomicInteger()
        val tracker = ActivityLeakTracker<Any>(maxRecheckCount = 1) { callbacks.incrementAndGet() }
        val retainedObject = Any()
        val reference = WeakReference(retainedObject)
        assertTrue(tracker.enqueue(reference, nowMillis = 0L, delayMillis = 0L))
        assertTrue(reference.get() === retainedObject)

        val skipped = tracker.scan(
            nowMillis = 0L,
            nextCheckDelayMillis = 20L,
            debuggerConnected = true,
            gcAndFinalize = { gcCalls.incrementAndGet() },
        )

        assertTrue(skipped.skippedByDebugger)
        assertEquals(1, skipped.deferredCount)
        assertEquals(0, gcCalls.get())
        assertEquals(0, callbacks.get())
        assertEquals(20L, tracker.nextDueAtMillis())

        val checked = tracker.scan(
            nowMillis = 20L,
            nextCheckDelayMillis = 20L,
            debuggerConnected = false,
            gcAndFinalize = { gcCalls.incrementAndGet() },
        )
        assertFalse(checked.skippedByDebugger)
        assertEquals(1, gcCalls.get())
        assertEquals(1, callbacks.get())
        assertEquals(0, tracker.size)
    }

    @Test
    fun rescheduleMovesExistingCandidateToCurrentScanCycle() {
        val tracker = ActivityLeakTracker<Any>(maxRecheckCount = 2) {}
        val retainedObject = Any()
        val reference = WeakReference(retainedObject)

        assertTrue(tracker.enqueue(reference, nowMillis = 100L, delayMillis = 5L))
        assertEquals(105L, tracker.nextDueAtMillis())

        tracker.reschedule(nowMillis = 200L, nextCheckDelayMillis = 50L)

        assertEquals(250L, tracker.nextDueAtMillis())
    }

    @Test
    fun duplicateEnqueueAndClearDoNotRetainCandidates() {
        val tracker = ActivityLeakTracker<Any>(maxRecheckCount = 1) {}
        val retainedObject = Any()
        val reference = WeakReference(retainedObject)

        assertTrue(tracker.enqueue(reference, 0L, 1L))
        assertFalse(tracker.enqueue(WeakReference(retainedObject), 0L, 1L))
        assertEquals(1, tracker.size)

        tracker.clear()

        assertEquals(0, tracker.size)
        assertEquals(null, tracker.nextDueAtMillis())
    }
}
