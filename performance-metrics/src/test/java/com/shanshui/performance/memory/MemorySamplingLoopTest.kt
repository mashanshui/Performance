package com.shanshui.performance.memory

import com.shanshui.performance.metrics.MemoryConfig
import com.shanshui.performance.metrics.MetricsServiceConfig
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Delayed
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 调度不追赶、状态立即采样及旧任务退出后接管的回归。 */
class MemorySamplingLoopTest {
    /** 执行器延迟不影响下一次完整等待，前后台重启取消旧周期。 */
    @Test fun delayedSamplingDoesNotCatchUpAndSwitchIsImmediate() {
        /** 受测串行采样执行器。 */
        val scheduler = ManualScheduler()
        try {
            /** 当前前后台状态，不决定运行身份。 */
            var foreground = true
            /** 已经执行的有效采样任务次数。 */
            var captures = 0
            /** 本实例的可取消单次采样调度。 */
            val loop = MemorySamplingLoop(scheduler, { if (foreground) 10_000 else 60_000 }, { captures++ })
            loop.restart()
            assertEquals(0L, scheduler.nextDelay())
            scheduler.runNext()
            assertEquals(1, captures)
            assertEquals(10_000L, scheduler.nextDelay())
            // 任意晚执行，只会排一个完整间隔，不使用固定频率追赶。
            scheduler.runNext()
            assertEquals(2, captures)
            assertEquals(10_000L, scheduler.nextDelay())
            foreground = false
            loop.restart()
            scheduler.runNext()
            assertEquals(3, captures)
            assertEquals(60_000L, scheduler.nextDelay())
            /** 已经执行的最终检查点次数。 */
            var checkpoints = 0
            loop.close { checkpoints++ }
            scheduler.runNext()
            assertEquals(1, checkpoints)
            loop.restart()
            assertTrue(scheduler.pending().isEmpty())
        } finally { scheduler.shutdownNow() }
    }

    /** close 不在调用线程写盘，新 Reporter 恢复必须排在旧采样和最终检查点之后。 */
    @Test fun reinitializeWaitsForOldWriterAndFinalCheckpoint() {
        /** 受测串行采样执行器。 */
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        /** 确认旧采样已经开始的同步门。 */
        val entered = CountDownLatch(1)
        /** 允许旧采样继续完成的同步门。 */
        val release = CountDownLatch(1)
        /** 确认下一实例已开始恢复的同步门。 */
        val completed = CountDownLatch(1)
        /** 线程实际执行顺序，用于验证接管边界。 */
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        /** 发起关闭的线程，不能在此执行持久化。 */
        val caller = Thread.currentThread()
        try {
            /** 模拟尚未退出的旧采样任务。 */
            val old = MemorySamplingLoop(scheduler, { 60_000 }, {
                assertNotSame(caller, Thread.currentThread())
                order += "old-start"
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                order += "old-finish"
            })
            old.restart()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            old.close { order += "checkpoint" }
            scheduler.execute { order += "new-restore"; completed.countDown() }
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("old-start", "old-finish", "checkpoint", "new-restore"), order)
        } finally { release.countDown(); scheduler.shutdownNow() }
    }

    /** 默认 TTL 与精简组件配置一致；周期正数校验和独立开关保持有效。 */
    @Test fun memoryRetentionDefaultsAndIntervalValidation() {
        assertEquals(10_000L, MemoryConfig().foregroundSamplingIntervalMillis)
        assertEquals(30_000L, MemoryConfig().backgroundSamplingIntervalMillis)
        assertEquals(90L * 86_400_000, MemoryConfig().eventTtlMillis)
        assertEquals(MemoryConfig().eventTtlMillis, MetricsServiceConfig().memoryEventTtlMillis)
        for (factory in listOf<() -> MemoryConfig>({ MemoryConfig(foregroundSamplingIntervalMillis = 0) }, { MemoryConfig(backgroundSamplingIntervalMillis = -1) })) {
            try { factory(); fail("invalid interval accepted") } catch (expected: IllegalArgumentException) { assertNotNull(expected.message) }
        }
        assertFalse(MemoryConfig(enabled = false).enabled)
    }

    /** 仅用于确定性单次调度，不启动真实线程、不模拟周期追赶。 */
    private class ManualScheduler : ScheduledThreadPoolExecutor(1) {
        /** 所有已安排句柄，取消项不参与下一步。 */ private val tasks = mutableListOf<Task>()
        /** 截获生产调度请求，保留完整等待间隔。 */
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> = Task(command, unit.toMillis(delay)).also { tasks += it }
        /** 最早到期的未取消任务。 */
        fun pending(): List<Task> = tasks.filter { !it.isDone }.sortedBy { it.delay }
        /** 用断言读取下一次等待，不自动推进真实时间。 */
        fun nextDelay(): Long = pending().first().delay
        /** 执行一次任务，生产逻辑自行安排后续。 */
        fun runNext() { pending().first().run() }
    }

    /** 可取消的测试句柄。 */
    private class Task(command: Runnable, /** 本次完整等待。 */ val delay: Long) : FutureTask<Unit>(command, Unit), ScheduledFuture<Unit> {
        /** 返回测试记录的等待时间。 */
        override fun getDelay(unit: TimeUnit): Long = unit.convert(delay, TimeUnit.MILLISECONDS)
        /** 等待顺序用于手工执行器。 */
        override fun compareTo(other: Delayed): Int = getDelay(TimeUnit.MILLISECONDS).compareTo(other.getDelay(TimeUnit.MILLISECONDS))
    }
}
