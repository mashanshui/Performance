package com.shanshui.performance

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
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

    /** 验证原有按钮文案保持一致。 */
    @Test
    fun crashTestButtonShowsExpectedText() {
        onView(withId(R.id.crashTestButton)).check(matches(withText(R.string.crash_test_entry)))
    }
    /** 导航与全部操作可见性回归，不触发会终止测试进程的崩溃。 */
    @Test
    fun separateCrashPageShowsAllCasesAndCanReturn() {
        onView(withId(R.id.crashCasesPageButton)).perform(click())
        onView(withText(R.string.crash_cases_title)).check(matches(isDisplayed()))
        onView(withId(R.id.explicitCrashButton)).check(matches(isDisplayed()))
        onView(withId(R.id.nullCrashButton)).check(matches(isDisplayed()))
        onView(withId(R.id.boundsCrashButton)).check(matches(isDisplayed()))
        onView(withId(R.id.stateCrashButton)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.asyncCrashButton)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.wrappedCrashButton)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.closeCrashPageButton)).perform(scrollTo(), click())
        onView(withId(R.id.crashCasesPageButton)).check(matches(isDisplayed()))
    }
}
