package com.example.nativelib.thread

import android.os.Process
import androidx.annotation.MainThread
import com.example.nativelib.NativeLib
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * @author mashanshui
 * @since 2025-12-21
 */
object ThreadUtils {
    fun bindMainThreadToMaxCore(): Boolean = NativeLib().bindMainThreadToMaxCore()

    fun bindCurrentThreadToMaxCore(): Boolean = NativeLib().bindCurrentThreadToMaxCore()

    @MainThread
    fun setMainThreadMaxPriority() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    }

    fun setRenderThreadMaxPriority() {
        Process.setThreadPriority(getRenderThreadTid(), Process.THREAD_PRIORITY_URGENT_AUDIO)
    }

    private fun getRenderThreadTid(): Int {
        val taskParent = File("/proc/" + Process.myPid() + "/task/")
        if (taskParent.isDirectory()) {
            val taskFiles = taskParent.listFiles()
            if (taskFiles != null) {
                for (taskFile in taskFiles) {
                    //读线程名
                    var br: BufferedReader? = null
                    var cpuRate = ""
                    try {
                        br = BufferedReader(FileReader(taskFile.getPath() + "/stat"), 100)
                        cpuRate = br.readLine()
                    } catch (throwable: Throwable) {
                        //ignore
                    } finally {
                        if (br != null) {
                            br.close()
                        }
                    }

                    if (!cpuRate.isEmpty()) {
                        val param: Array<String> =
                            cpuRate.split(" ".toRegex()).dropLastWhile { it.isEmpty() }
                                .toTypedArray()
                        if (param.size < 2) {
                            continue
                        }

                        val threadName = param[1]
                        //找到name为RenderThread的线程，则返回第0个数据就是 tid
                        if (threadName == "(RenderThread)") {
                            return param[0].toInt()
                        }
                    }
                }
            }
        }
        return -1
    }
}