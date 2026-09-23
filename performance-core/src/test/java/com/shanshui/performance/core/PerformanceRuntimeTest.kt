package com.shanshui.performance.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 显式组件装配、失败回滚和逆序关闭测试。 */
class PerformanceRuntimeTest {
    /** 验证重复组件在任何启动前被拒绝。 */
    @Test
    fun rejectsDuplicateComponentIdsBeforeStart() {
        val first = RecordingComponent("crash")
        val second = RecordingComponent("crash")
        assertThrows(IllegalArgumentException::class.java) {
            PerformanceRuntime.create(listOf(first, second))
        }
        assertEquals(emptyList<String>(), first.events)
        assertEquals(emptyList<String>(), second.events)
    }

    /** 验证启动失败时已成功组件按逆序关闭，并且关闭幂等。 */
    @Test
    fun rollsBackInReverseOrderAndClosesIdempotently() {
        val events = mutableListOf<String>()
        val first = RecordingComponent("crash", events)
        val second = RecordingComponent("metrics", events, failOnStart = true)
        val runtime = PerformanceRuntime.create(listOf(first, second))

        assertThrows(IllegalStateException::class.java) { runtime.start() }
        runtime.close()
        assertEquals(
            listOf(
                "prepare:crash",
                "prepare:metrics",
                "start:crash",
                "start:metrics",
                "close:metrics",
                "close:crash",
            ),
            events,
        )
    }

    /** 验证相同描述复用实例、不同描述拒绝并保持原实例可用。 */
    @Test
    fun registryReusesSameInitializationAndRejectsConflict() {
        PerformanceRuntimeRegistry.resetForTesting()
        val first = RecordingComponent("crash")
        val existing = PerformanceRuntimeRegistry.initialize(listOf(first), "same")
        val reused = PerformanceRuntimeRegistry.initialize(listOf(RecordingComponent("crash")), "same")
        assertEquals(existing, reused)
        assertThrows(IllegalStateException::class.java) {
            PerformanceRuntimeRegistry.initialize(listOf(RecordingComponent("metrics")), "different")
        }
        PerformanceRuntimeRegistry.close()
    }

    /** 记录最小生命周期事件以验证运行时顺序。 */
    private class RecordingComponent(
        /** 组件唯一标识。 */
        override val id: String,
        /** 共享事件列表。 */
        val events: MutableList<String> = mutableListOf(),
        /** 是否在启动阶段故意失败。 */
        private val failOnStart: Boolean = false,
    ) : PerformanceComponent {
        /** 记录进程准备。 */
        override fun prepare() {
            events += "prepare:$id"
        }

        /** 记录采集启动并按测试要求失败。 */
        override fun start() {
            events += "start:$id"
            if (failOnStart) {
                throw IllegalStateException("start failed: $id")
            }
        }

        /** 记录关闭。 */
        override fun close() {
            events += "close:$id"
        }
    }
}
