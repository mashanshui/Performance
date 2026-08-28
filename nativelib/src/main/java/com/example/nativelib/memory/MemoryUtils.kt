package com.example.nativelib.memory

import android.os.Build
import android.os.Debug
import android.util.Log

/**
 * @author mashanshui
 * @since 2026-05-02
 */
object MemoryUtils {
    const val TAG = "MemoryUtils"

    fun getMemoryInfo(): String {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            info.memoryStats.forEach {
                Log.d(TAG, "getMemoryInfo: ${it.key} ${it.value}")
            }
        }
        return "MemoryInfo: ${info.toString()}"
    }
}