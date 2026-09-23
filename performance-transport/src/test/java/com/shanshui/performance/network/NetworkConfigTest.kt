package com.shanshui.performance.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transport 配置校验和凭据脱敏测试。 */
class NetworkConfigTest {
    /** 验证基础地址规范化并保留协议字段。 */
    @Test
    fun normalizesBaseUrl() {
        val config = NetworkConfig("http://localhost:8080", "secret")
        assertEquals("http://localhost:8080/", config.normalizedBaseUrl.toString())
        assertTrue(config.toString().contains("<redacted>"))
        assertTrue(!config.toString().contains("secret"))
    }

    /** 验证非法地址、凭据和超时被拒绝。 */
    @Test
    fun rejectsInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkConfig("not-a-url", "key")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkConfig("http://localhost", " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkConfig("http://localhost", "key", connectTimeoutMillis = 0)
        }
    }
}
