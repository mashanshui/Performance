package com.example.nativelib.cpuusage

import com.example.nativelib.NativeLib

/**
 * @author mashanshui
 * @since 2025-12-21
 */
object BindCoreUtils {
    fun bindMainThreadToMaxCore(): Boolean = NativeLib().bindMainThreadToMaxCore()

    fun bindCurrentThreadToMaxCore(): Boolean = NativeLib().bindCurrentThreadToMaxCore()
}