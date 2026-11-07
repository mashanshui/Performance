package com.shanshui.performance

import android.os.SystemClock
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 页面验证与产物验收分开：无采集来源时不要求伪造成功结果。 */
@RunWith(AndroidJUnit4::class)
class JankPageInstrumentedTest {
    @Test fun openJankPageAndReturn() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.jankTestButton)).perform(click())
            onView(withText(R.string.jank_test_title)).check(matches(isDisplayed()))
            onView(withId(R.id.jankStatusText)).check(matches(withText(R.string.jank_test_ready)))
            onView(withId(R.id.jankDuration1000)).check(matches(isChecked()))
            onView(withId(R.id.closeJankPageButton)).perform(scrollTo(), click())
            onView(withId(R.id.jankTestButton)).check(matches(isDisplayed()))
        }
    }

    @Test fun allScenariosRespectDurationAndBlockRepeatedRequests() {
        ActivityScenario.launch(TestJankActivity::class.java).use { scenario ->
            listOf(R.id.triggerJankButton, R.id.triggerSleepJankButton, R.id.triggerSyncJankButton).forEach { id ->
                waitUntil(scenario) { it.findViewById<Button>(id).isEnabled }
                scenario.onActivity { activity ->
                    activity.findViewById<RadioGroup>(R.id.jankDurationGroup).check(R.id.jankDuration300)
                    activity.findViewById<Button>(id).performClick()
                    assertFalse(activity.findViewById<Button>(id).isEnabled)
                    assertFalse(activity.findViewById<android.view.View>(R.id.jankDuration3000).isEnabled)
                    // 直接调用 performClick 绕过控件禁用状态，验证逻辑层也阻止重复提交。
                    activity.findViewById<Button>(id).performClick()
                    assertTrue(text(activity).contains("准备执行"))
                }
                waitUntil(scenario) { it.findViewById<Button>(id).isEnabled }
                scenario.onActivity { activity ->
                    val result = text(activity)
                    assertTrue(result.contains("设定时长：300"))
                    assertTrue(result.contains("实际耗时："))
                    if (id != R.id.triggerSyncJankButton) assertTrue(result.contains("手工抓栈调用次数：0"))
                    else assertFalse(result.contains("手工抓栈调用次数：0\n"))
                    assertTrue(result.contains("导出") || result.contains("未导出文件"))
                    android.util.Log.i("JankPageValidation", result)
                    val available = PerformanceSdk.current()?.isJankAvailable == true &&
                        listOf(com.shanshui.performance.jank.JankDiagnostics.getSyncSamplingStatus(),
                            com.shanshui.performance.jank.JankDiagnostics.getAsyncSamplingStatus()).any {
                            it == com.shanshui.performance.jank.JankSamplingStatus.RUNNING
                        }
                    if (available && (id == R.id.triggerSyncJankButton || com.shanshui.performance.jank.JankDiagnostics.getAsyncSamplingStatus() ==
                            com.shanshui.performance.jank.JankSamplingStatus.RUNNING)) {
                        assertTrue(result.contains("导出状态：成功") || result.contains("导出状态：部分覆盖"))
                        assertTrue(result.contains("已入队"))
                        activity.findViewById<Button>(R.id.copyJankPathButton).performClick()
                        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().endsWith(".rheajank.zip"))
                    } else if (!available) {
                        assertTrue(result.contains("没有可用采集来源"))
                        assertFalse(activity.findViewById<Button>(R.id.copyJankPathButton).isEnabled)
                    }
                }
            }
        }
    }

    @Test fun leavingCancelsPendingLoadAndReenteringDoesNotRunIt() {
        ActivityScenario.launch(TestJankActivity::class.java).use { scenario ->
            waitUntil(scenario) { it.findViewById<Button>(R.id.triggerJankButton).isEnabled }
            scenario.onActivity { it.findViewById<Button>(R.id.triggerJankButton).performClick() }
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(200)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertTrue(text(it).contains("已离开页面"))
                assertFalse(text(it).contains("实际耗时"))
                assertTrue(it.findViewById<Button>(R.id.triggerJankButton).isEnabled)
            }
        }
    }

    private fun text(activity: TestJankActivity) = activity.findViewById<TextView>(R.id.jankStatusText).text.toString()

    /** 等待真实就绪/完成状态，不以主线程暂时空闲推断异步导出已完成。 */
    private fun waitUntil(scenario: ActivityScenario<TestJankActivity>, predicate: (TestJankActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        do {
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("等待卡顿页面状态超时")
    }
}
