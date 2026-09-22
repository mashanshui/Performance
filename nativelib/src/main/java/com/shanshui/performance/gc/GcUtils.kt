package com.shanshui.performance.gc

import com.shanshui.performance.NativeLib

/**
 * @author mashanshui
 * @since 2025/12/23
 */
object GcUtils {
    fun openGcInhibit(seconds: Int) {
        NativeLib().openGcInhibit(seconds)
    }
}