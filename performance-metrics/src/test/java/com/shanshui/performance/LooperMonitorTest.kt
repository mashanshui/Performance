package com.shanshui.performance

import android.util.Printer
import com.shanshui.performance.LooperMonitor.AttachmentState
import com.shanshui.performance.LooperMonitor.RecordingConfig
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 使用可控时钟和 Printer 存储验证，不依赖 Android 私有字段或真实时间等待。 */
class LooperMonitorTest {
    private var now = 0L
    private val errors = mutableListOf<Exception>()
    private val core = LooperDispatchCore({ now }, errors::add)
    private open class Listener : LooperMonitor.LooperListener {
        val events = mutableListOf<String>()
        var valid = true
        override fun isValid() = valid
        override fun onMessageBegin(log: String, beginNs: Long) { events.add("B:$beginNs") }
        override fun onMessageEnd(log: String, beginNs: Long, endNs: Long) { events.add("E:$beginNs:$endNs") }
    }
    private class Access : LooperPrinterAccess {
        var current: Printer? = null
        var fail = false
        var writes = 0
        override fun read(): Printer? {
            if (fail) throw IllegalAccessException("blocked")
            return current
        }
        override fun write(printer: Printer?) { writes++; current = printer }
    }
    private val access = Access()
    private val hook = LooperPrinterHook(access, core, { now }, errors::add)
    private fun message(cost: Long = 10) {
        core.dispatch("> dispatch")
        now += cost
        core.dispatch("< finish")
    }

    @Test fun invalidLogsAndUnpairedEdgesDoNotPoisonFollowingMessages() {
        val listener = Listener()
        core.register(listener)
        listOf(null, "", "noise", "< orphan").forEach(core::dispatch)
        core.dispatch("> first")
        now = 5
        core.dispatch("> duplicate")
        core.dispatch("noise")
        now = 10
        core.dispatch("< done")
        core.dispatch("< duplicate")
        assertEquals(listOf("B:0", "E:0:10"), listener.events)
        message()
        assertEquals(4, listener.events.size)
    }

    @Test fun duplicateRegistrationAndMidMessageRegistration() {
        val first = Listener()
        val late = Listener()
        core.register(first)
        core.register(first)
        core.dispatch("> start")
        core.register(late)
        core.dispatch("< end")
        assertEquals(2, first.events.size)
        assertTrue(late.events.isEmpty())
        message()
        assertEquals(2, late.events.size)
    }

    @Test fun callbackCanChangeListenersWithoutHoldingCollectionLock() {
        val removed = Listener()
        val added = Listener()
        val completed = CountDownLatch(1)
        val first = object : Listener() {
            override fun onMessageBegin(log: String, beginNs: Long) {
                Thread {
                    core.unregister(removed)
                    core.register(added)
                    completed.countDown()
                }.start()
                assertTrue(completed.await(2, TimeUnit.SECONDS))
                core.unregister(this)
            }
        }
        core.register(first)
        core.register(removed)
        message()
        assertTrue(first.events.isEmpty())
        assertTrue(removed.events.isEmpty())
        assertTrue(added.events.isEmpty())
        message()
        assertEquals(2, added.events.size)
    }

    @Test fun validityCheckedAtStartAndExceptionsDoNotBreakPairing() {
        val throwing = object : Listener() {
            override fun onMessageBegin(log: String, beginNs: Long) { throw IllegalStateException("begin") }
            override fun onMessageEnd(log: String, beginNs: Long, endNs: Long) { throw IllegalStateException("end") }
        }
        val healthy = Listener()
        core.register(throwing)
        core.register(healthy)
        core.dispatch("> start")
        healthy.valid = false
        now = 10
        core.dispatch("< end")
        assertEquals(listOf("B:0", "E:0:10"), healthy.events)
        message()
        assertEquals(2, healthy.events.size)
        assertEquals(4, errors.size)
    }

    @Test fun throwingValidityAndUnregisterReregisterSkipCurrentEnd() {
        val invalid = object : Listener() { override fun isValid(): Boolean = error("valid") }
        val listener = Listener()
        core.register(invalid)
        core.register(listener)
        core.dispatch("> start")
        core.unregister(listener)
        core.register(listener)
        core.dispatch("< end")
        assertEquals(listOf("B:0"), listener.events)
        assertEquals(1, errors.size)
    }

    @Test fun recordingDefaultsOffAndFlagsAreIndependent() {
        message()
        assertTrue(core.history(true).isEmpty())
        assertEquals(0L, core.recent().completedCount)
        core.configure(RecordingConfig(denseEnabled = true))
        message()
        assertTrue(core.history(true).isEmpty())
        assertEquals(1L, core.recent().completedCount)
        core.configure(RecordingConfig(historyEnabled = true))
        message()
        assertEquals(1, core.history(false).size)
        assertEquals(0L, core.recent().completedCount)
    }

    @Test fun boundedSnapshotsKeepCumulativeTotalsAndAreImmutable() {
        core.configure(RecordingConfig(true, true, 2, 2))
        repeat(3) { message() }
        val history = core.history(false)
        val recent = core.recent()
        assertEquals(listOf(10L, 20L), history.map { it.beginNs })
        assertEquals(2, recent.messages.size)
        assertEquals(3L, recent.completedCount)
        assertEquals(30L, recent.durationNs)
        assertThrows(UnsupportedOperationException::class.java) { (history as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (recent.messages as MutableList).clear() }
        message()
        assertEquals(10L, history.first().beginNs)
    }

    @Test fun currentSnapshotDoesNotAppendOrMutateCompletedHistory() {
        core.configure(RecordingConfig(historyEnabled = true))
        core.dispatch("> current")
        now = 7
        repeat(3) {
            val current = core.history(true).single()
            assertNull(current.endNs)
            assertEquals(7L, current.durationNs)
            assertEquals("> current", current.log)
        }
        assertTrue(core.history(false).isEmpty())
        now = 10
        core.dispatch("< done")
        assertEquals(10L, core.history(false).single().durationNs)
    }



    @Test fun clearAndConfigurationChangesExcludeInFlightMessages() {
        core.configure(RecordingConfig(true, true))
        message()
        core.dispatch("> in flight")
        core.clearRecent()
        now += 10
        core.dispatch("< end")
        assertEquals(0L, core.recent().completedCount)
        assertEquals(2, core.history(false).size)
        core.dispatch("> reconfigure")
        core.configure(RecordingConfig(true, true, 1, 1))
        core.dispatch("< end")
        assertTrue(core.history(true).isEmpty())
        assertEquals(0L, core.recent().completedCount)
        message()
        assertEquals(1L, core.recent().completedCount)
        assertEquals(1, core.history(false).size)
    }

    @Test fun closeInsideCallbackCancelsRemainingValidityChecks() {
        core.register(object : Listener() {
            override fun onMessageBegin(log: String, beginNs: Long) { core.close() }
        })
        core.register(object : Listener() {
            override fun isValid(): Boolean = throw AssertionError("关闭后不应调用快照成员")
        })
        message()
        assertEquals(0, core.listenerCount)
    }

    @Test fun identicalConfigurationKeepsRecordsAndRecoveryFailureDoesNotOverwrite() {
        val config = RecordingConfig(true, true)
        core.configure(config)
        message()
        core.configure(config)
        assertEquals(1, core.history(false).size)
        assertEquals(1L, core.recent().completedCount)
        hook.install()
        val unknown = Printer { }
        access.current = unknown
        access.fail = true
        now = 60_000
        hook.onIdle()
        hook.close()
        assertSame(unknown, access.current)
        assertEquals(1, errors.size)
    }

    @Test fun capacitiesValidatedAndClosedCoreRejectsNewWork() {
        assertThrows(IllegalArgumentException::class.java) { RecordingConfig(historyCapacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { RecordingConfig(recentCapacity = -1) }
        val listener = Listener()
        core.register(listener)
        core.dispatch("> start")
        core.close()
        core.close()
        core.dispatch("< end")
        assertEquals(listOf("B:0"), listener.events)
        assertEquals(0, core.listenerCount)
        assertTrue(core.history(true).isEmpty())
        assertThrows(IllegalStateException::class.java) { core.register(listener) }
        assertThrows(IllegalStateException::class.java) { core.configure(RecordingConfig()) }
    }

    @Test fun printerForwardsAllLogsAndRestoresOnlyOwnedPrinter() {
        val logs = mutableListOf<String?>()
        val origin = Printer { logs.add(it) }
        access.current = origin
        hook.install()
        val owned = access.current!!
        hook.install()
        assertSame(owned, access.current)
        owned.println("")
        owned.println("> start")
        owned.println("< end")
        assertEquals(listOf("", "> start", "< end"), logs)
        hook.close()
        hook.close()
        assertSame(origin, access.current)
        assertEquals(AttachmentState.CLOSED, hook.state)
        owned.println("after close")
        assertEquals("after close", logs.last())
    }

    @Test fun idleRecoveryIsThrottledAndDoesNotDuplicateWrappedCallbacks() {
        val listener = Listener()
        core.register(listener)
        hook.install()
        val old = access.current!!
        val thirdParty = Printer { old.println(it) }
        access.current = thirdParty
        now = 59_999
        hook.onIdle()
        assertSame(thirdParty, access.current)
        now = 60_000
        hook.onIdle()
        assertNotSame(thirdParty, access.current)
        access.current!!.println("> start")
        now++
        access.current!!.println("< end")
        assertEquals(listOf("B:60000", "E:60000:60001"), listener.events)
        hook.close()
        assertSame(thirdParty, access.current)
    }

    @Test fun thirdPartyOwnershipSurvivesCloseAndRecursionIsBlocked() {
        val listener = Listener()
        core.register(listener)
        access.current = Printer { access.current!!.println(it) }
        hook.install()
        access.current!!.println("> start")
        access.current!!.println("< end")
        assertEquals(2, listener.events.size)
        val replacement = Printer { }
        access.current = replacement
        hook.close()
        assertSame(replacement, access.current)
    }

    @Test fun reflectionFailureIsReportedOnceAndNeverOverwritesUnknownPrinter() {
        val origin = Printer { }
        access.current = origin
        access.fail = true
        hook.install()
        now = 120_000
        hook.onIdle()
        hook.install()
        assertEquals(AttachmentState.REFLECTION_UNAVAILABLE, hook.state)
        hook.close()
        assertEquals(1, errors.size)
        assertEquals(0, access.writes)
        assertSame(origin, access.current)
    }

    @Test fun recoveryDropsIncompleteMessageAndSameClassIsNotWrapped() {
        val listener = Listener()
        core.register(listener)
        hook.install()
        val other = LooperPrinterHook(access, LooperDispatchCore({ now }, errors::add), { now }, errors::add)
        other.install()
        assertEquals(AttachmentState.UNAVAILABLE, other.state)
        access.current!!.println("> lost end")
        access.current = Printer { }
        now = 60_000
        hook.onIdle()
        access.current!!.println("< orphan")
        access.current!!.println("> next")
        access.current!!.println("< end")
        assertEquals(listOf("B:0", "B:60000", "E:60000:60000"), listener.events)
    }
}
