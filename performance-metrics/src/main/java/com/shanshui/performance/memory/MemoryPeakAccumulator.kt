package com.shanshui.performance.memory

import com.shanshui.performance.network.MemoryMetricEvent
import com.shanshui.performance.network.MemoryPeak

/** 纯内存累计器：保持原运行信封、独立峰值和首次等值上下文。 */
internal class MemoryPeakAccumulator(
    /** 首次有效采样时才持久化的原始信封，或恢复的完整快照。 */
    initial: MemoryMetricEvent,
) {
    /** 当前完整候选；只有存储成功后才能被称为已持久化。 */
    var event: MemoryMetricEvent = initial
        private set

    /** 累计有效读数，返回是否出现新高；全部失败不推进观测截止。 */
    fun observe(values: MemorySampleValues, now: Long, foreground: Boolean, scene: String?): Boolean {
        if (!values.hasValue()) return false
        /** 前一次候选中的独立峰值。 */
        val previous = event.memoryPeakSummary
        /** 回退墙钟不能使峰值跑出本轮区间。 */
        val sampledAt = maxOf(now, previous.collectionStartedAt, previous.lastSampledAt)
        /** 仅保留合法前台场景。 */
        val safeScene = scene?.takeIf { foreground && it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl) }
        /** 三项分别比较后的完整候选。 */
        val updated = previous.copy(
            lastSampledAt = sampledAt,
            pss = peak(previous.pss, values.pssBytes, sampledAt, foreground, safeScene),
            vss = peak(previous.vss, values.vssBytes, sampledAt, foreground, safeScene),
            javaHeap = peak(previous.javaHeap, values.javaHeapUsedBytes, sampledAt, foreground, safeScene),
        )
        event = event.copy(occurredAt = sampledAt, memoryPeakSummary = updated)
        return previous.pss != updated.pss || previous.vss != updated.vss || previous.javaHeap != updated.javaHeap
    }

    /** 仅严格创新高时替换单项；负数/越界读数按缺失处理。 */
    private fun peak(old: MemoryPeak?, bytes: Long?, now: Long, foreground: Boolean, scene: String?): MemoryPeak? =
        if (bytes != null && bytes in 0..MemorySampler.MAX_MEMORY_BYTES && (old == null || bytes > old.bytes)) {
            MemoryPeak(bytes, now, foreground, scene)
        } else old
}
