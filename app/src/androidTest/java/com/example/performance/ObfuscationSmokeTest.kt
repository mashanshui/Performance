package com.example.performance

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.nativelib.NativeLib
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 在混淆后的 Release 变体验证示例页面和 JNI 名称绑定。 */
@RunWith(AndroidJUnit4::class)
class ObfuscationSmokeTest {
    /** 启动主页面以覆盖 Release Application 和 Activity 的实际加载。 */
    @get:Rule
    val activityScenarioRule = ActivityScenarioRule(MainActivity::class.java)

    /** 验证主页面能够启动，且 C++ 导出符号仍能通过 NativeLib 调用。 */
    @Test
    fun mainActivityAndNativeJniMethodWorkInRelease() {
        // ActivityScenarioRule 已启动 MainActivity；R.id 不属于 JNI 符号烟测范围。
        assertEquals("Hello from C++", NativeLib().stringFromJNI())
    }
}
