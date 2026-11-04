package com.shanshui.performance

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 验证首页导航、真实计算触发及返回，不将页面成功等同于产物上传成功。 */
@RunWith(AndroidJUnit4::class)
class JankPageInstrumentedTest {
    /** 每个测试从首页进入，覆盖实际入口而非直接启动测试页。 */
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    /** 进入页面不会自动触发计算，点击后等待主线程任务完成，再返回首页。 */
    @Test
    fun openJankPageTriggerTestAndReturn() {
        onView(withId(R.id.jankTestButton)).check(matches(isDisplayed())).perform(click())
        onView(withText(R.string.jank_test_title)).check(matches(isDisplayed()))
        onView(withId(R.id.jankStatusText)).check(matches(withText(R.string.jank_test_ready)))
        onView(withId(R.id.triggerJankButton)).perform(scrollTo(), click())
        onView(withId(R.id.jankStatusText)).check(matches(withText(R.string.jank_test_completed)))
        onView(withId(R.id.closeJankPageButton)).perform(scrollTo(), click())
        onView(withId(R.id.jankTestButton)).check(matches(isDisplayed()))
    }
}
