package com.example.performance.demo

import android.util.Log
import com.example.nativelib.gc.GcUtils
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * @author mashanshui
 * @since 2025/12/23
 */
class GcInhibitDemo {
    val TAG = "GcInhibitDemo"
    fun test() {
        logGcDetail()
        GcUtils.openGcInhibit(3)
        repeat(1000000) {
            TestObject("sdfkhsadf asdf sadf" + it)
        }
        logGcDetail()
//        Runtime.getRuntime().gc()
        GlobalScope.launch {
            repeat(20) {
                delay(1000)
                logGcDetail()
            }
        }
    }

    fun logGcDetail() {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        Log.e(TAG, "logGcDetail maxMemory: $maxMemory, totalMemory: $totalMemory, freeMemory: $freeMemory")
    }

    class TestObject(var value: String)
}