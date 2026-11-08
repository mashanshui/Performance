package com.shanshui.performance.memory

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Reporter 的单次采样调度，状态变化取消旧周期，完成后才计算下一次等待。 */
internal class MemorySamplingLoop(
    /** 同进程所有 Reporter 共用的串行执行器。 */ private val scheduler: ScheduledExecutorService,
    /** 当前状态对应间隔。 */ private val interval: () -> Long,
    /** 后台完整采样路径。 */ private val capture: () -> Unit,
) {
    /** 状态切换代数，正在执行的旧采样不能再续排旧周期。 */ private var generation = 0
    /** 本实例是否已经停止。 */ private var closed = false
    /** 仅本实例的下次采样。 */ private var future: ScheduledFuture<*>? = null

    /** 初始化和状态切换立即采样，不重置累计器。 */
    @Synchronized fun restart() {
        if (closed) return
        generation++
        future?.cancel(false)
        schedule(generation, 0)
    }

    /** 取消本实例后提交最终检查点，串行执行器留给后续 Reporter 接管。 */
    @Synchronized fun close(checkpoint: () -> Unit) {
        if (closed) return
        closed = true
        generation++
        future?.cancel(false)
        scheduler.execute(checkpoint)
    }

    /** 以完成时刻为起点等待，不补发迟到的周期；异常不让后续采样永久停止。 */
    private fun schedule(expectedGeneration: Int, delay: Long) {
        future = scheduler.schedule(sampleTask@{
            synchronized(this) { if (closed || expectedGeneration != generation) return@sampleTask }
            try { capture() } finally {
                synchronized(this) {
                    if (!closed && expectedGeneration == generation) schedule(expectedGeneration, interval())
                }
            }
        }, delay, TimeUnit.MILLISECONDS)
    }
}
