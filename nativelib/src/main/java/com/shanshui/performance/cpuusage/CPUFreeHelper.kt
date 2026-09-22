package com.shanshui.performance.cpuusage

import android.util.Log
import com.shanshui.performance.NativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * @author mashanshui
 * @since 2025-12-20
 */
object CPUFreeHelper {
    const val CPU_SPEED_IDLE_VALUE = 0.1
    const val TAG = "CPUFreeHelper"

    inline class Level private constructor(val value: Int) {
        companion object {
            val LOW = Level(0)
            val MIDDLE = Level(1)
            val HIGH = Level(2)
        }
    }

    private val nativeLib by lazy { NativeLib() }
    private val mCpuCoreNum by lazy { Runtime.getRuntime().availableProcessors() }
    private var mStop = false
    var mAcquireInterval = 1000L
    private var mBeforeCpuTime = 0f
    private var mBeforeSysTime = 0L
    private val mCpuFreeListenerList = ConcurrentHashMap<Level, MutableList<CpuFreeListener>>()

    fun start() {
        GlobalScope.launch {
            mBeforeCpuTime = nativeLib.getCpuTime()
            mBeforeSysTime = System.currentTimeMillis()
            while (!mStop) {
                delay(mAcquireInterval)
                val timeInterval = (System.currentTimeMillis() - mBeforeSysTime) / 1000.toFloat()
                val cpuTime = nativeLib.getCpuTime()
                val usagePercentage = (cpuTime - mBeforeCpuTime) / (timeInterval * mCpuCoreNum)
                Log.e(TAG, "CPU利用率: $usagePercentage")
                if (usagePercentage < CPU_SPEED_IDLE_VALUE) {
                    Log.e(TAG, "CPU空闲")
                    withContext(Dispatchers.Main) {
                        notifyCpuFree(usagePercentage)
                    }
                }
            }
        }
    }

    fun stop() {
        mStop = true
    }

    fun registerCallback(level: Level, cpuFreeListener: CpuFreeListener) {
        var listeners = mCpuFreeListenerList.get(level)
        if (listeners == null) {
            listeners = mutableListOf()
        }
        listeners.add(cpuFreeListener)
        mCpuFreeListenerList[level] = listeners
    }

    fun unRegisterCallback(cpuFreeListener: CpuFreeListener) {
        mCpuFreeListenerList.forEach {
            it.value.remove(cpuFreeListener)
        }
    }

    /**
     * 根据任务的等级回调监听，每次检测到CPU空闲，就从对应的等级的监听列表中取出一个监听，回调后移除
     */
    private fun notifyCpuFree(usagePercentage: Float) {
        var freeListeners = mCpuFreeListenerList[Level.HIGH]
        if (!freeListeners.isNullOrEmpty()) {
            freeListeners[0].onCpuFree(usagePercentage)
            freeListeners.removeAt(0)
            return
        }
        freeListeners = mCpuFreeListenerList[Level.MIDDLE]
        if (!freeListeners.isNullOrEmpty()) {
            freeListeners[0].onCpuFree(usagePercentage)
            freeListeners.removeAt(0)
            return
        }
        freeListeners = mCpuFreeListenerList[Level.LOW]
        if (!freeListeners.isNullOrEmpty()) {
            freeListeners[0].onCpuFree(usagePercentage)
            freeListeners.removeAt(0)
            return
        }
    }
}