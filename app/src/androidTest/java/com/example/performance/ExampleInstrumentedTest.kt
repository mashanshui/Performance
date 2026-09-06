package com.example.performance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 在设备进程中确认被测应用包名，作为仪器测试环境的基础校验。 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    /** 读取目标应用 Context，并验证测试没有连接到错误的包。 */
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.performance", appContext.packageName)
    }
}
