package com.shanshui.performance.memory

import com.shanshui.performance.network.MemoryMetricEvent

/** 仅由采样串行线程拥有的恢复、累计和检查点状态机。 */
internal class MemoryRunRecorder(
    /** current 与历史存储。 */ private val store: MemoryEventStore,
    /** 本进程共享身份。 */ private val processId: String,
    /** 首次有效采样才固定原运行元数据和 eventId。 */ private val eventFactory: () -> MemoryMetricEvent,
) {
    /** 恢复或封存成功前禁止采样新轮。 */ var ready: Boolean = false
        private set
    /** 内存候选，包含失败后仍待重试的新高。 */ private var accumulator: MemoryPeakAccumulator? = null
    /** 上次成功提交的完整版本。 */ private var persisted: MemoryMetricEvent? = null

    /** 启动可重试；封存失败不会建立新轮。 */
    fun start() {
        if (ready) return
        persisted = store.restore(processId)
        accumulator = persisted?.let(::MemoryPeakAccumulator)
        ready = true
    }

    /** 新高立即提交；无新高只更新候选截止，不追加普通事件。 */
    fun observe(values: MemorySampleValues, now: Long, foreground: Boolean, scene: String?) {
        start()
        if (!values.hasValue()) return
        /** 只在首个有效读数固定原信封。 */
        val current = accumulator ?: MemoryPeakAccumulator(eventFactory()).also { accumulator = it }
        if (current.observe(values, now, foreground, scene) || persisted == null) checkpoint()
    }

    /** 周期和 close 尽力提交；保存失败保留候选，后续检查点再次尝试。 */
    fun checkpoint() {
        /** 保存失败仍保留此候选，后续检查点继续重试。 */
        val candidate = accumulator?.event ?: return
        if (candidate != persisted) {
            store.saveCurrent(candidate)
            persisted = candidate
        }
    }
}
