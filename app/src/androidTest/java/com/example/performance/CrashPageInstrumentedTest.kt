package com.example.performance

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证主页面提供崩溃上传测试入口；真正的崩溃由独立设备烟测触发。 */
@RunWith(AndroidJUnit4::class)
class CrashPageInstrumentedTest {
    /** 为每个用例创建主页面场景。 */
    @get:Rule
    val activityScenarioRule = ActivityScenarioRule(MainActivity::class.java)

    /** 验证崩溃测试按钮已显示，避免在通用仪器测试中主动杀死测试进程。 */
    @Test
    fun crashTestButtonIsDisplayed() {
        onView(withId(R.id.crashTestButton)).check(matches(isDisplayed()))
    }

    /** 验证按钮文案与 Debug 测试用途一致。 */
    @Test
    fun crashTestButtonShowsExpectedText() {
        onView(withId(R.id.crashTestButton)).check(matches(withText(R.string.crash_test_entry)))
    }
}
