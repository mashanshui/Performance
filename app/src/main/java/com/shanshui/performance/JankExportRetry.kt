package com.shanshui.performance

import com.shanshui.performance.jank.JankEvent
import com.shanshui.performance.jank.JankExportCallback
import com.shanshui.performance.jank.JankExportRequestStatus

/** 只对 BUSY 重试；取消后不再提交，但已受理的导出仍自然完成。 */
internal class JankExportRetry(
    private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val export: (JankEvent, JankExportCallback) -> JankExportRequestStatus,
) {
    private var generation = 0L

    fun cancel() {
        ++generation
    }

    fun submit(
        event: JankEvent,
        callback: JankExportCallback,
        accepted: () -> Unit,
        rejected: (JankExportRequestStatus) -> Unit,
    ) {
        val run = ++generation
        val deadline = now() + 3000L
        fun attempt() {
            if (run != generation) return
            when (val status = export(event, callback)) {
                JankExportRequestStatus.ACCEPTED -> accepted()
                JankExportRequestStatus.BUSY -> {
                    if (now() >= deadline) rejected(status)
                    else schedule(100L) { attempt() }
                }
                else -> rejected(status)
            }
        }
        attempt()
    }
}
