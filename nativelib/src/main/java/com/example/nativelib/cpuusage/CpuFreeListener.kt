package com.example.nativelib.cpuusage

/**
 * @author mashanshui
 * @since 2025-12-21
 */
interface CpuFreeListener {
    fun onCpuFree(usagePercentage: Float)
}