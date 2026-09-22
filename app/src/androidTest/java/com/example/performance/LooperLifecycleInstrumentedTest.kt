package com.shanshui.performance

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shanshui.nativelib.LooperMonitor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 验证页面重建不累积主线程监听，销毁后恢复原监听数量。 */
@RunWith(AndroidJUnit4::class)
class LooperLifecycleInstrumentedTest {
    @Test fun recreationKeepsSingleListenerAndDestroyUnregisters() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val monitor = LooperMonitor.sMainMonitor
        val baseline = monitor.listenerCount
        val scenario = ActivityScenario.launch(MainActivity::class.java)
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
}
