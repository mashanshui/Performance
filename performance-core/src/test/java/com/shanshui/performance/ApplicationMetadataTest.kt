package com.shanshui.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Application 元数据派生规则测试。 */
class ApplicationMetadataTest {
    /** 验证版本名和版本号组成默认 buildId。 */
    @Test
    fun derivesBuildIdFromVersionAndCode() {
        val metadata = ApplicationMetadataResolver.fromValues(
            packageName = "com.example.performance",
            versionName = "2.4.1",
            versionCode = 17,
        )
        assertEquals("com.example.performance", metadata.packageName)
        assertEquals("2.4.1", metadata.versionName)
        assertEquals(17, metadata.versionCode)
        assertEquals("2.4.1-17", metadata.defaultBuildId)
    }

    /** 验证版本号超出协议范围时拒绝配置。 */
    @Test
    fun rejectsVersionCodeOutsideProtocolRange() {
        assertThrows(IllegalArgumentException::class.java) {
            ApplicationMetadataResolver.fromValues(
                packageName = "com.example.performance",
                versionName = "1.0",
                versionCode = Int.MAX_VALUE.toLong() + 1,
            )
        }
    }
}
