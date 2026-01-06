package com.example.nativelib.fps

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi

/**
 * @author mashanshui
 * @since 2026-01-01
 */
class FpsHelperV2 {
    companion object {
        private const val TAG = "FpsHelper"
    }

    private var mIsOnlyCollectScroll = false

    private val mHandler = Handler(Looper.getMainLooper())
    private val mRunnable = Runnable {
        mIsScrolling = false
        collect()
    }
    private var mLastFrameTimeNanos = 0L
    private var mDropFrameCount = 0
    private var mFrameCount = 0
    private var mIsScrolling = false
    private var mRefreshRate = 60
    private var mFrameIntervalMillis = 1000 / mRefreshRate
    private var mHitTotalTime = 0L
    private var mLastTime = 0L

    @RequiresApi(Build.VERSION_CODES.N)
    fun start(window: Window, isOnlyCollectScroll: Boolean = false) {
        mIsOnlyCollectScroll = isOnlyCollectScroll
        if (mIsOnlyCollectScroll) {
            window.decorView.viewTreeObserver.addOnScrollChangedListener {
                mIsScrolling = true
                mHandler.removeCallbacks(mRunnable)
                mHandler.postDelayed(mRunnable, 100)
            }
        }
        window.addOnFrameMetricsAvailableListener({ window, frameMetrics, dropCountSinceLastInvocation ->
            if (mIsOnlyCollectScroll && !mIsScrolling) {
                return@addOnFrameMetricsAvailableListener
            }
            if (mFrameCount == 0) {
                mLastTime = System.currentTimeMillis()
                mRefreshRate = window.windowManager.defaultDisplay.refreshRate.toInt()
                mFrameIntervalMillis = 1000 / mRefreshRate
            }
            val total = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val frameTimeMillis = (total / 1000000).toInt()
//            Log.e(TAG, "onCreate: $frameTimeMillis")
            val jitter = Math.max(mFrameIntervalMillis, frameTimeMillis)
            if (jitter > mFrameIntervalMillis) {
                mHitTotalTime += jitter
                mDropFrameCount += jitter / mFrameIntervalMillis
            }
            mFrameCount++
            val now = System.currentTimeMillis()
            if (now - mLastTime > 1000) {
                collect()
                mLastTime = now
            }
            Log.d(TAG, "doFrame: FrameCount:$mFrameCount DropFrameCount:$mDropFrameCount HitTotalTime:$mHitTotalTime")
        }, Handler())
    }

    private fun collect() {
        sumData()
        reset()
    }

    private fun sumData() {
        val now = System.currentTimeMillis()
        val timeConsume = now - mLastTime
        val scrollHitchRate = mHitTotalTime * 100f / timeConsume
        Log.e(TAG, "sumData: TimeInterval:$timeConsume FrameCount:$mFrameCount DropFrameCount:$mDropFrameCount ScrollHitchRate:$scrollHitchRate%")
    }

    private fun reset() {
        mLastFrameTimeNanos = 0L
        mDropFrameCount = 0
        mFrameCount = 0
        mLastTime = 0L
        mHitTotalTime = 0L
    }
}