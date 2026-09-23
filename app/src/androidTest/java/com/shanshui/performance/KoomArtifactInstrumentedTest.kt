package com.shanshui.performance

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在当前模块化示例 App 中触发真实 Activity 泄漏并检查 KOOM report 队列。 */
@RunWith(AndroidJUnit4::class)
class KoomArtifactInstrumentedTest {
    /** 验证泄漏确认、KOOM dump/分析回调和 report JSON 已进入持久目录。 */
    @Test
    fun leakProducesPersistentReportArtifact() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch(TestMemoryLeakActivity::class.java)
        onView(withId(R.id.leakDestroyButton)).perform(click())
        scenario.close()

        // 保持宿主进程处于前台，满足 KOOM 启动 HeapAnalysisService 的前台条件。
        val foregroundScenario = ActivityScenario.launch(MainActivity::class.java)
        val reportRoot = File(targetContext.noBackupFilesDir, REPORT_DIRECTORY)
        try {
            // 示例 App 使用一次重检和一秒 GC 延迟；轮询覆盖 dump、分析进程和 report 回调的异步窗口。
            val deadline = SystemClock.uptimeMillis() + REPORT_WAIT_MILLIS
            while (SystemClock.uptimeMillis() < deadline) {
                val reportReady = reportRoot.walkTopDown()
                    .any { it.isFile && it.name == REPORT_FILE_NAME }
                if (reportReady) break
                SystemClock.sleep(REPORT_POLL_INTERVAL_MILLIS)
            }
        } finally {
            foregroundScenario.close()
        }
        val reportFiles = reportRoot.walkTopDown()
            .filter { it.isFile && it.name == REPORT_FILE_NAME }
            .toList()
        assertTrue("KOOM report queue was not created: ${reportRoot.absolutePath}", reportRoot.isDirectory)
        assertTrue("KOOM report JSON was not persisted", reportFiles.isNotEmpty())
    }

    /** KOOM dump 和独立分析进程的最大等待时间。 */
    private companion object {
        /** report 队列目录。 */
        const val REPORT_DIRECTORY = "performance-memory-leak-reporter"
        /** report 内容文件名。 */
        const val REPORT_FILE_NAME = "report.json"
        /** 等待异步 dump、分析和回调的最长时间。 */
        const val REPORT_WAIT_MILLIS = 60_000L
        /** report 队列轮询间隔。 */
        const val REPORT_POLL_INTERVAL_MILLIS = 1_000L
    }
}
