package com.shanshui.performance

import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shanshui.performance.LooperMonitor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 验证卡顿页独立管理监听，首页、暂停和销毁均不残留示例监听。 */
@RunWith(AndroidJUnit4::class)
class LooperLifecycleInstrumentedTest {
    /** 验证重建和销毁过程不会重复注册主线程监听。 */
    @Test
    fun recreationKeepsSingleListenerAndDestroyUnregisters() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val monitor = LooperMonitor.sMainMonitor
        val baseline = monitor.listenerCount
        val scenario = ActivityScenario.launch(TestJankActivity::class.java)
        try {
            scenario.onActivity { assertEquals(baseline + 1, monitor.listenerCount) }
            repeat(3) {
                scenario.recreate()
                scenario.onActivity { assertEquals(baseline + 1, monitor.listenerCount) }
            }
            assertEquals(LooperMonitor.AttachmentState.ATTACHED, monitor.attachmentState)
        } finally { scenario.close() }
        instrumentation.waitForIdleSync()
        assertEquals(baseline, monitor.listenerCount)
    }

    /** 首页只提供导航，不再注册卡顿示例监听。 */
    @Test
    fun mainPageDoesNotRegisterJankListener() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val monitor = LooperMonitor.sMainMonitor
        val baseline = monitor.listenerCount
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { assertEquals(baseline, monitor.listenerCount) }
        } finally { scenario.close() }
    }

    /** 暂停时清理监听，恢复时只注册一次，避免后台或反复切换累积监听。 */
    @Test
    fun pausedJankPageUnregistersAndResumeRegistersOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val monitor = LooperMonitor.sMainMonitor
        val baseline = monitor.listenerCount
        val scenario = ActivityScenario.launch(TestJankActivity::class.java)
        try {
            repeat(2) {
                scenario.onActivity { assertEquals(baseline + 1, monitor.listenerCount) }
                scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.waitForIdleSync()
                assertEquals(baseline, monitor.listenerCount)
                scenario.moveToState(Lifecycle.State.RESUMED)
            }
            scenario.onActivity { assertEquals(baseline + 1, monitor.listenerCount) }
        } finally { scenario.close() }
        instrumentation.waitForIdleSync()
        assertEquals(baseline, monitor.listenerCount)
    }
}
