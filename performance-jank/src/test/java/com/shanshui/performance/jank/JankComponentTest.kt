package com.shanshui.performance.jank

import com.bytedance.rheatrace.RheaTrace3
import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证 Jank 公共状态对 Rhea 状态的完整映射，避免第三方枚举泄漏到 SDK API。 */
class JankComponentTest {
    /** 验证支持的状态、失败状态、错误码和 reporter 就绪标志均被保留。 */
    @Test
    fun mapsRheaInitStatesToSdkStates() {
        val mappings = mapOf(
            RheaTrace3.InitResult.STARTED to JankInitStatus.STARTED,
            RheaTrace3.InitResult.ALREADY_STARTED to JankInitStatus.ALREADY_STARTED,
            RheaTrace3.InitResult.DISABLED to JankInitStatus.DISABLED,
            RheaTrace3.InitResult.UNSUPPORTED_DEVICE to JankInitStatus.UNSUPPORTED_DEVICE,
            RheaTrace3.InitResult.NOT_MAIN_PROCESS to JankInitStatus.NOT_MAIN_PROCESS,
            RheaTrace3.InitResult.MODE_CONFLICT to JankInitStatus.FAILED,
            RheaTrace3.InitResult.INVALID_CONFIG to JankInitStatus.FAILED,
            RheaTrace3.InitResult.NATIVE_INIT_FAILED to JankInitStatus.FAILED,
        )

        mappings.forEach { (rheaStatus, expectedStatus) ->
            val result = JankComponent.mapInitResult(
                JankArtifactInitResult(
                    traceResult = rheaStatus,
                    reporterReady = true,
                    errorCode = "TEST_ERROR",
                ),
            )
            assertEquals(expectedStatus, result.status)
            assertEquals(true, result.reporterReady)
            assertEquals("TEST_ERROR", result.errorCode)
        }
    }
}
