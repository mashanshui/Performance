package com.shanshui.performance.fps

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowManager

/**
 * @author mashanshui
 * @since 2026-01-01
 */
class FpsHelper {
    companion object {
        private const val TAG = "FpsHelper"
    }

    private val mHandler = Handler(Looper.getMainLooper())
    private val mRunnable = Runnable {
        mIsScrolling = false
    }
    private var mLastFrameTimeNanos = 0L
    private var mDropFrameCount = 0
    private var mFrameCount = 0
    private var mIsScrolling = false
    private var mRefreshRate = 60f
    private var mFrameIntervalMillis = 1000 / mRefreshRate
    private var mHitTotalTime = 0L
    private var mLastTime = 0L

    fun start(view: View, context: Context) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mRefreshRate = windowManager.defaultDisplay.refreshRate
        mFrameIntervalMillis = 1000 / mRefreshRate
        view.viewTreeObserver.addOnScrollChangedListener {
            if (!mIsScrolling) {
                mIsScrolling = true
                startCollect()
            }
            mHandler.removeCallbacks(mRunnable)
            mHandler.postDelayed(mRunnable, 100)
        }
    }

    private fun startCollect() {
        val mFrameCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (mLastFrameTimeNanos != 0L) {
                    val frameCost = frameTimeNanos - mLastFrameTimeNanos
                    val jitter = frameCost / 1000000
                    if (jitter > mFrameIntervalMillis) {
                        mHitTotalTime += jitter
                        mDropFrameCount += (jitter / mFrameIntervalMillis).toInt()
                    }
                    mFrameCount++
                    val now = System.currentTimeMillis()
                    if (now - mLastTime > 1000) {
                        sumData()
                        reset()
                        mLastTime = now
                    }
                } else {
                    mLastTime = System.currentTimeMillis()
                }
                mLastFrameTimeNanos = frameTimeNanos
                Log.d(TAG, "doFrame: FrameCount:$mFrameCount DropFrameCount:$mDropFrameCount HitTotalTime:$mHitTotalTime")
                if (mIsScrolling) {
                    Choreographer.getInstance().postFrameCallback(this)
                } else {
                    stopCollect()
                }
            }
        }
        Choreographer.getInstance().postFrameCallback(mFrameCallback)
    }

    private fun stopCollect() {
        sumData()
        reset()
    }

    private fun sumData() {
        val now = System.currentTimeMillis()
        val timeConsume = now - mLastTime
        val scrollHitchRate = mHitTotalTime * 100f / timeConsume
        Log.e(TAG, "sumData: TimeInterval:$timeConsume FrameCount:$mFrameCount DropFrameCount:$mDropFrameCount ScrollHitchRate:$scrollHitchRate")
    }

    private fun reset() {
        mLastFrameTimeNanos = 0L
        mDropFrameCount = 0
        mFrameCount = 0
        mLastTime = 0L
        mHitTotalTime = 0L
    }
}