package com.shanshui.performance.thread

import android.os.Process
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author mashanshui
 * @since 2025-12-23
 */
class CoreThreadFactory(val mPrefix: String, val priority: Int) : ThreadFactory {
    private val mThreadNum = AtomicInteger(1)

    override fun newThread(r: Runnable): Thread {
        val name = "$mPrefix-${mThreadNum.getAndIncrement()}"
        return Thread(AdjustThreadPriority(priority, r), name)
    }

    class AdjustThreadPriority(val priority: Int, val runnable: Runnable) : Runnable {
        override fun run() {
            Process.setThreadPriority(priority)
            runnable.run()
        }
    }
}