package com.example.nativelib.fps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** fps-v1 纳秒计算和空桶规则测试。 */
class FpsHelperV2Test {
    /** 验证 60 Hz 满速帧归一化后仍为 60 FPS。 */
    @Test
    fun sixtyHzFullSpeedIsNormalizedToSixty() {
        val accumulator = FpsFrameAccumulator("home", refreshRateHz = 60.0)

        repeat(60) { accumulator.addFrame(16_666_666L, 1_000L + it) }

        val summary = accumulator.snapshot()
        requireNotNull(summary)
        assertEquals(60L, summary.uiRefreshFrameCount)
        assertEquals(60.0, summary.normalizedFps60 ?: 0.0, 0.01)
    }

    /** 验证 90/120 Hz 满速帧统一折算到 60 Hz。 */
    @Test
    fun highRefreshRateIsNormalizedToSixty() {
        val ninety = FpsFrameAccumulator("home", refreshRateHz = 90.0)
        val oneTwenty = FpsFrameAccumulator("home", refreshRateHz = 120.0)

        repeat(90) { ninety.addFrame(11_111_111L, 1_000L + it) }
        repeat(120) { oneTwenty.addFrame(8_333_333L, 2_000L + it) }

        assertEquals(60.0, ninety.snapshot()?.normalizedFps60 ?: 0.0, 0.01)
        assertEquals(60.0, oneTwenty.snapshot()?.normalizedFps60 ?: 0.0, 0.01)
    }

    /** 验证两倍帧预算耗时会得到约 30 的归一化 FPS。 */
    @Test
    fun twiceBudgetProducesThirtyFps() {
        val accumulator = FpsFrameAccumulator("slow", refreshRateHz = 60.0)

        repeat(60) { accumulator.addFrame(33_333_332L, 1_000L + it) }

        assertEquals(30.0, accumulator.snapshot()?.normalizedFps60 ?: 0.0, 0.01)
    }

    /** 验证无效耗时不产生帧，静止区间不会生成零帧快照。 */
    @Test
    fun invalidFrameAndEmptyBucketAreIgnored() {
        val accumulator = FpsFrameAccumulator("idle", refreshRateHz = 60.0)

        assertFalse(accumulator.addFrame(0L, 1_000L))
        assertFalse(accumulator.addFrame(-1L, 1_001L))
        assertTrue(accumulator.snapshot() == null)
    }

    /** 验证回调丢失数只进入诊断字段，不会补造 UI 刷新帧。 */
    @Test
    fun callbackDropsAreDiagnosticOnly() {
        val accumulator = FpsFrameAccumulator("scroll", refreshRateHz = 60.0)

        accumulator.addCallbackDrops(3)
        accumulator.addFrame(16_666_666L, 1_000L)

        val summary = requireNotNull(accumulator.snapshot())
        assertEquals(1L, summary.uiRefreshFrameCount)
        assertEquals(3L, summary.callbackDropCount)
    }

    /** 验证取快照后重置只影响后续区间，不改变桶身份。 */
    @Test
    fun snapshotAndResetStartsAnEmptyInterval() {
        val accumulator = FpsFrameAccumulator("detail", refreshRateHz = 60.0)
        accumulator.addFrame(16_666_666L, 1_000L)

        assertTrue(accumulator.snapshotAndReset() != null)
        assertTrue(accumulator.snapshot() == null)
    }
}
