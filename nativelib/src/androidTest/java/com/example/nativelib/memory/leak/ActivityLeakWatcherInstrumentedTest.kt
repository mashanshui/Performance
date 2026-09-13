package com.example.nativelib.memory.leak

import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nativelib.MemoryLeakConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 验证真实 Android 线程上的生命周期回调、后台扫描和 close 注销边界。 */
@RunWith(AndroidJUnit4::class)
class ActivityLeakWatcherInstrumentedTest {
    @Test
    fun realActivityDestroyInvokesBackgroundCallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val application = activity.application as Application
        val callbackCount = AtomicInteger()
        val callbackThread = AtomicReference<Thread?>()
        val gcCount = AtomicInteger()
        val callbackDone = CountDownLatch(1)
        val watcher = ActivityLeakWatcher(
            application = application,
            config = MemoryLeakConfig(
                foregroundScanIntervalMillis = 50L,
                backgroundScanIntervalMillis = 50L,
                maxRecheckCount = 1,
                gcDelayMillis = 1L,
                skipWhenDebuggerConnected = false,
            ),
            onLeakDetected = {
                callbackThread.set(Thread.currentThread())
                callbackCount.incrementAndGet()
                callbackDone.countDown()
            },
            isDebuggerConnected = { false },
            gcAndFinalize = { gcCount.incrementAndGet() },
        )
        try {
            // 由真实 Activity finish 触发 Application.ActivityLifecycleCallbacks，而非手工调用回调。
            instrumentation.runOnMainSync { activity.finish() }
            assertTrue(callbackDone.await(5L, TimeUnit.SECONDS))
            assertEquals(1, callbackCount.get())
            assertTrue(gcCount.get() >= 1)
            assertNotSame(Looper.getMainLooper().thread, callbackThread.get())
        } finally {
            watcher.close()
        }
    }

    @Test
    fun closeUnregistersBeforeRealActivityDestroy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val application = activity.application as Application
        val callbackCount = AtomicInteger()
        val watcher = ActivityLeakWatcher(
            application = application,
            config = testConfig(),
            onLeakDetected = { callbackCount.incrementAndGet() },
            isDebuggerConnected = { false },
            gcAndFinalize = {},
        )
        try {
            watcher.close()
            assertFalse(watcher.isAvailable)
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            assertEquals(0, callbackCount.get())
            assertEquals(0, watcher.pendingCandidateCount())
        } finally {
            watcher.close()
        }
    }

    private fun launchActivity(instrumentation: Instrumentation): LeakTestActivity {
        val intent = Intent(instrumentation.context, LeakTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return instrumentation.startActivitySync(intent) as LeakTestActivity
    }

    private fun testConfig(): MemoryLeakConfig {
        return MemoryLeakConfig(
            foregroundScanIntervalMillis = 50L,
            backgroundScanIntervalMillis = 50L,
            maxRecheckCount = 1,
            gcDelayMillis = 1L,
            skipWhenDebuggerConnected = false,
        )
    }
}
