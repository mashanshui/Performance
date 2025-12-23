package com.example.nativelib.gc

import com.example.nativelib.NativeLib

/**
 * @author mashanshui
 * @since 2025/12/23
 */
object GcUtils {
    fun openGcInhibit(seconds: Int) {
        NativeLib().openGcInhibit(seconds)
    }
}