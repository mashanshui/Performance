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
    private var beginMs = 0L

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
            val frameMetricsCopy = FrameMetrics(frameMetrics)
            mRefreshRate = window.windowManager.defaultDisplay.refreshRate.toInt()
            mFrameIntervalMillis = 1000 / mRefreshRate
            if (mFrameCount == 0) {
                beginMs = System.currentTimeMillis()
            }
            val total = frameMetricsCopy.getMetric(FrameMetrics.TOTAL_DURATION)
            val frameTimeMillis = (total / 1000000).toInt()
            val jitter = Math.max(mFrameIntervalMillis, frameTimeMillis)
//            Log.e(TAG, "onCreate: $frameTimeMillis  jitter:$jitter")
            if (jitter > mFrameIntervalMillis) {
                mHitTotalTime += jitter
                mDropFrameCount += jitter / mFrameIntervalMillis
            }
            mFrameCount++
            val now = System.currentTimeMillis()
            if (now - beginMs > 1000) {
                collect()
                beginMs = now
            }
//            Log.d(TAG, "doFrame: FrameCount:$mFrameCount DropFrameCount:$mDropFrameCount HitTotalTime:$mHitTotalTime RefreshRate:$mRefreshRate")
        }, Handler())
    }

    private fun collect() {
        sumData()
        reset()
    }

    private fun sumData() {
        Log.e(TAG, "sumData: FrameCount:$mFrameCount DropFrameCount:$mDropFrameCount")
    }

    private fun reset() {
        mLastFrameTimeNanos = 0L
        mDropFrameCount = 0
        mFrameCount = 0
        beginMs = 0L
        mHitTotalTime = 0L
    }
}