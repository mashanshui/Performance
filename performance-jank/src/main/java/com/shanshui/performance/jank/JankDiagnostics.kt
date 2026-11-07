package com.shanshui.performance.jank

import com.bytedance.rheatrace.RheaTrace3

/** 独立采集来源的实际运行状态。 */
enum class JankSamplingStatus {
    DISABLED, INITIALIZING, RUNNING, PAUSED, UNSUPPORTED, FAILED, STOPPED
}

/** SDK 自有的卡顿堆栈诊断门面，隐藏 Rhea 的静态类型和枚举。 */
object JankDiagnostics {
    /** 查询同步来源，不把第三方枚举暴露给宿主。 */
    fun getSyncSamplingStatus(): JankSamplingStatus = mapSamplingStatus(RheaTrace3.getSyncSamplingStatus())

    /** 查询异步来源；初始化结果不能替代此实时状态。 */
    fun getAsyncSamplingStatus(): JankSamplingStatus = mapSamplingStatus(RheaTrace3.getAsyncSamplingStatus())

    internal fun mapSamplingStatus(status: RheaTrace3.SamplingStatus): JankSamplingStatus =
        JankSamplingStatus.valueOf(status.name)

    /** 开始当前线程的堆栈计时会话。 */
    fun beginStackTiming() {
        RheaTrace3.beginStackTiming()
    }

    /** 结束当前线程计时并返回文本诊断结果。 */
    fun endStackTiming(): String = RheaTrace3.endStackTiming()

    /** 请求采集当前堆栈；调用线程由宿主自行决定。 */
    fun captureStackTrace(force: Boolean = false) {
        RheaTrace3.captureStackTrace(force)
    }
}
