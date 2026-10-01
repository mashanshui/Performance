package com.shanshui.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 核验异常类型及运行时输入缺失边界，异常在 JVM 测试中由断言捕获。 */
class CrashTestCasesTest {
    /** 主动抛出的异常类型和标识稳定。 */
    @Test fun explicitFailure() {
        // 仅在测试中捕获，不改变设备页面的未捕获异常链路。
        val error = assertThrows(IllegalStateException::class.java) { CrashTestCases.explicitException() }
        assertEquals("Crash test: explicit failure", error.message)
    }

    /** 空对象解引用确实产生 JVM 空指针异常。 */
    @Test fun nullDereference() {
        assertThrows(NullPointerException::class.java) { CrashTestCases.nullPointer() }
    }

    /** 索引越界确实产生 JVM 数组越界异常。 */
    @Test fun outOfBounds() {
        assertThrows(ArrayIndexOutOfBoundsException::class.java) { CrashTestCases.arrayBounds() }
    }

    /** 不同失败状态具有相同错误信息，无法凭消息确定实际输入。 */
    @Test fun runtimeStateDoesNotRevealInput() {
        CrashTestCases.runtimeState("ready")
        // 两个独立状态用于验证输入确实不会进入错误消息。
        val expired = assertThrows(IllegalStateException::class.java) { CrashTestCases.runtimeState("expired") }
        val missing = assertThrows(IllegalStateException::class.java) { CrashTestCases.runtimeState("missing") }
        assertEquals("Crash test: runtime state unavailable", expired.message)
        assertEquals(expired.message, missing.message)
    }

    /** 不同外部响应具有相同错误信息，成功响应正常返回。 */
    @Test fun externalResponseDoesNotRevealInput() {
        CrashTestCases.externalResponse("accepted")
        // 此处只验证异常逻辑，后台线程的真实致命处理由设备烟测验证。
        val malformed = assertThrows(IllegalArgumentException::class.java) { CrashTestCases.externalResponse("malformed") }
        val denied = assertThrows(IllegalArgumentException::class.java) { CrashTestCases.externalResponse("denied") }
        assertEquals("Crash test: external response rejected", malformed.message)
        assertEquals(malformed.message, denied.message)
    }
}
