package com.example.performance

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实 Android 环境验证主页面入口和 FPS 测试页面核心控件。 */
@RunWith(AndroidJUnit4::class)
class FpsPageInstrumentedTest {
    /** 每个测试从干净的主页面开始，避免前一个场景影响入口验证。 */
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    /** 点击主页面 FPS 入口，并检查测试控件和列表已经显示。 */
    @Test
    fun openFpsPageFromMainActivity() {
        onView(withId(R.id.fpsTestButton))
            .check(matches(isDisplayed()))
            .perform(click())
        onView(withId(R.id.startAutoScrollButton)).check(matches(isDisplayed()))
        onView(withId(R.id.stopAutoScrollButton)).check(matches(isDisplayed()))
        onView(withId(R.id.injectSlowFrameButton)).check(matches(isDisplayed()))
        onView(withId(R.id.restoreDefaultSceneButton)).check(matches(isDisplayed()))
        onView(withId(R.id.recyclerView)).check(matches(isDisplayed()))
    }
}
