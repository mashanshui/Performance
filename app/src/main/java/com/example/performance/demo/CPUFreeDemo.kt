package com.example.performance.demo

import com.example.nativelib.cpuusage.CPUFreeHelper
import com.example.nativelib.cpuusage.CpuFreeListener
import kotlin.concurrent.thread

/**
 * @author mashanshui
 * @since 2025-12-21
 */
class CPUFreeDemo {
    fun test() {
        CPUFreeHelper.registerCallback(CPUFreeHelper.Level.HIGH, object : CpuFreeListener {
            override fun onCpuFree(usagePercentage: Float) {

            }
        })
        CPUFreeHelper.start()
        thread {
            var i = 1
            while (i < 1000000000) {
                i++
            }
        }
        thread {
            var i = 1
            while (i < 1000000000) {
                i++
            }
        }
    }
}