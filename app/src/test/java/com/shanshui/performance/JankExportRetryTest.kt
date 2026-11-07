package com.shanshui.performance

import com.shanshui.performance.jank.*
import org.junit.Assert.*
import org.junit.Test

/** 使用可控时钟验证繁忙重试、离页取消及新测试替换旧重试。 */
class JankExportRetryTest {
    private var clock = 0L
    private val tasks = ArrayDeque<() -> Unit>()
    private val events = mutableListOf<JankEvent>()
    private var status = JankExportRequestStatus.BUSY
    private var accepted = 0
    private val rejected = mutableListOf<JankExportRequestStatus>()
    private val retry = JankExportRetry({ clock }, { delay, task ->
        tasks.add { clock += delay; task() }
    }, { event, _ -> events.add(event); status })
    private val event = JankEvent("event", 1L, "session", "scene", 1L, 2L, 1L, 0L)
    private fun submit() = retry.submit(event, JankExportCallback {}, { accepted++ }, { rejected.add(it) })

    @Test fun busyRetriesSameEventThenAccepts() {
        submit()
        status = JankExportRequestStatus.ACCEPTED
        tasks.removeFirst()()
        assertEquals(100L, clock)
        assertEquals(listOf(event, event), events)
        assertEquals(1, accepted)
        assertTrue(rejected.isEmpty())
        assertTrue(tasks.isEmpty())
    }

    @Test fun busyStopsAtThreeSeconds() {
        submit()
        while (tasks.isNotEmpty()) tasks.removeFirst()()
        assertEquals(3000L, clock)
        assertEquals(listOf(JankExportRequestStatus.BUSY), rejected)
    }

    @Test fun cancellationAndReplacementIgnoreOldScheduledAttempts() {
        submit()
        retry.cancel()
        tasks.removeFirst()()
        assertEquals(1, events.size)
        submit()
        submit()
        status = JankExportRequestStatus.ACCEPTED
        while (tasks.isNotEmpty()) tasks.removeFirst()()
        assertEquals(4, events.size)
        assertEquals(1, accepted)
    }

    @Test fun otherRejectionsNeverRetry() {
        JankExportRequestStatus.values().filter { it !in listOf(
            JankExportRequestStatus.ACCEPTED, JankExportRequestStatus.BUSY) }.forEach {
            status = it
            submit()
            assertEquals(it, rejected.last())
            assertTrue(tasks.isEmpty())
        }
    }
}
