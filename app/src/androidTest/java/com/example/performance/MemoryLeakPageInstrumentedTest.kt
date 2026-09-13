package com.example.performance

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实 Android 环境验证内存泄漏测试页面的入口和操作控件。 */
@RunWith(AndroidJUnit4::class)
class MemoryLeakPageInstrumentedTest {
    /** 每个测试从主页面开始，避免沿用上一次测试的页面状态。 */
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    /** 打开测试页面，并验证正常销毁、制造泄漏和清理引用三个操作可用。 */
    @Test
    fun openMemoryLeakPageFromMainActivity() {
        onView(withId(R.id.memoryLeakTestButton))
            .check(matches(isDisplayed()))
            .perform(click())
        onView(withId(R.id.normalDestroyButton)).check(matches(isDisplayed()))
        onView(withId(R.id.leakDestroyButton)).check(matches(isDisplayed()))
        onView(withId(R.id.clearLeakReferenceButton)).check(matches(isDisplayed()))
        onView(withId(R.id.clearLeakReferenceButton)).perform(click())
    }
}
