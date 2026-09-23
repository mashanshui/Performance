package com.shanshui.performance.jank

import com.bytedance.rheatrace.RheaTrace3

/** SDK 自有的卡顿堆栈诊断门面，隐藏 Rhea 的静态类型和枚举。 */
object JankDiagnostics {
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
