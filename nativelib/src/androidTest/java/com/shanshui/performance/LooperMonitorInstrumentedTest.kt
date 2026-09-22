package com.shanshui.performance

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Printer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 真实 Looper 验证；每个工作线程均在 finally 中释放，避免污染其他测试。 */
@RunWith(AndroidJUnit4::class)
class LooperMonitorInstrumentedTest {
    private fun <T> onLooper(looper: Looper, block: () -> T): T {
        val task = FutureTask(block)
        assertTrue(Handler(looper).post(task))
        return task.get(10, TimeUnit.SECONDS)
    }
    private fun printer(looper: Looper): Printer? = Looper::class.java.getDeclaredField("mLogging").run {
        isAccessible = true
        get(looper) as Printer?
    }

    @Test fun realPrinterCoexistenceRecoveryCloseAndRecreation() {
        val thread = HandlerThread("looper-monitor-test").apply { start() }
        val looper = thread.looper
        val originalCount = AtomicInteger()
        val origin = Printer { originalCount.incrementAndGet() }
        var monitor: LooperMonitor? = null
        try {
            onLooper(looper) { looper.setMessageLogging(origin) }
            val first = LooperMonitor.of(looper).also { monitor = it }
            onLooper(looper) { }
            assertSame(first, LooperMonitor.of(looper))
            assertEquals(LooperMonitor.AttachmentState.ATTACHED, first.attachmentState)
            val armed = AtomicBoolean(false)
            val begins = AtomicInteger()
            val ends = AtomicInteger()
            var done = CountDownLatch(1)
            val listener = object : LooperMonitor.LooperListener {
                override fun isValid() = armed.get()
                override fun onMessageBegin(log: String, beginNs: Long) {
                    assertSame(looper.thread, Thread.currentThread())
                    begins.incrementAndGet()
                }
                override fun onMessageEnd(log: String, beginNs: Long, endNs: Long) {
                    assertSame(looper.thread, Thread.currentThread())
                    assertTrue(endNs >= beginNs)
                    ends.incrementAndGet()
                    armed.set(false)
                    done.countDown()
                }
            }
            first.register(listener)
            first.register(listener)
            assertEquals(1, first.listenerCount)
            armed.set(true)
            onLooper(looper) { SystemClock.sleep(5) }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(1, begins.get())
            assertEquals(1, ends.get())
            assertTrue(originalCount.get() >= 2)

            // 仅推进检查基线，实际恢复仍由真实队列的 IdleHandler 触发。
            val replaced = onLooper(looper) {
                val old = printer(looper)!!
                val thirdParty = Printer { old.println(it) }
                looper.setMessageLogging(thirdParty)
                val hook = LooperMonitor::class.java.getDeclaredField("hook").run {
                    isAccessible = true
                    get(first)
                }
                hook.javaClass.getDeclaredField("lastCheck").apply { isAccessible = true }
                    .setLong(hook, SystemClock.uptimeMillis() - 60_000L)
                thirdParty
            }
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (printer(looper) === replaced && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10)
            assertNotSame(replaced, printer(looper))
            done = CountDownLatch(1)
            armed.set(true)
            onLooper(looper) { }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(2, begins.get())
            assertEquals(2, ends.get())
            first.close()
            first.close()
            assertSame(replaced, printer(looper))
            assertEquals(0, first.listenerCount)
            assertEquals(LooperMonitor.AttachmentState.CLOSED, first.attachmentState)
            val second = LooperMonitor.of(looper).also { monitor = it }
            onLooper(looper) { }
            assertNotSame(first, second)
            assertEquals(LooperMonitor.AttachmentState.ATTACHED, second.attachmentState)
            second.close()
            assertSame(replaced, printer(looper))
            assertEquals(2, ends.get())
        } finally {
            monitor?.close()
            thread.quitSafely()
            thread.join(5_000)
        }
    }

    @Test fun closePreservesThirdPartyAndPendingInstallCanBeCancelled() {
        val thread = HandlerThread("looper-close-test").apply { start() }
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var monitor: LooperMonitor? = null
        try {
            Handler(thread.looper).post { entered.countDown(); resume.await(10, TimeUnit.SECONDS) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val first = LooperMonitor.of(thread.looper).also { monitor = it }
            assertEquals(LooperMonitor.AttachmentState.PENDING, first.attachmentState)
            first.close()
            resume.countDown()
            onLooper(thread.looper) { }
            assertNull(printer(thread.looper))
            val second = LooperMonitor.of(thread.looper).also { monitor = it }
            val replacement = Printer { }
            onLooper(thread.looper) { thread.looper.setMessageLogging(replacement) }
            second.close()
            assertSame(replacement, printer(thread.looper))
        } finally {
            resume.countDown()
            monitor?.close()
            thread.quitSafely()
            thread.join(5_000)
        }
    }

    @Test fun mainLooperDeliversOnMainThreadAndHistorySnapshotsAreBounded() {
        val monitor = LooperMonitor.sMainMonitor
        val done = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val listener = object : LooperMonitor.LooperListener {
            override fun isValid() = armed.get()
            override fun onMessageBegin(log: String, beginNs: Long) { assertSame(Looper.getMainLooper(), Looper.myLooper()) }
            override fun onMessageEnd(log: String, beginNs: Long, endNs: Long) {
                assertTrue(endNs >= beginNs)
                armed.set(false)
                done.countDown()
            }
        }
        try {
            onLooper(monitor.looper) { monitor.configureRecording(LooperMonitor.RecordingConfig(true, true, 2, 2)) }
            monitor.register(listener)
            armed.set(true)
            onLooper(monitor.looper) { SystemClock.sleep(5) }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            repeat(4) { onLooper(monitor.looper) { } }
            assertTrue(monitor.historySnapshot(false).size <= 2)
            val snapshot = monitor.recentSnapshot()
            assertTrue(snapshot.messages.size <= 2)
            assertTrue(snapshot.completedCount >= 4)
            assertTrue(snapshot.durationNs > 0)
        } finally {
            monitor.unregister(listener)
            monitor.close()
        }
    }
}
