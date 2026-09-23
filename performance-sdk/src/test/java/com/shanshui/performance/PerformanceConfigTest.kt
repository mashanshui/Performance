package com.shanshui.performance

import com.shanshui.performance.metrics.FpsConfig
import com.shanshui.performance.metrics.FpsLogLevel
import com.shanshui.performance.metrics.MemoryConfig
import com.shanshui.performance.metrics.MetricsServiceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证聚合配置和独立 Metrics 配置不依赖 Crash 配置。 */
class PerformanceConfigTest {
    /** 验证默认共享元数据、FPS 和内存参数。 */
    @Test
    fun usesDocumentedDefaults() {
        val config = PerformanceConfig()
        assertEquals(ServiceConfig.DEFAULT_ENVIRONMENT, config.service.environment)
        assertEquals(ServiceConfig.DEFAULT_CHANNEL, config.service.channel)
        assertEquals(FpsLogLevel.OFF, config.fps.logLevel)
        assertTrue(config.fps.enabled)
        assertTrue(config.memory.enabled)
    }

    /** 验证服务地址和超时等共享字段的边界校验。 */
    @Test
    fun rejectsInvalidServiceValues() {
        assertThrows(IllegalArgumentException::class.java) {
            ServiceConfig(baseUrl = "ftp://invalid.example")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServiceConfig(connectTimeoutMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServiceConfig(buildId = " ")
        }
    }

    /** 验证仅 Metrics 配置可以单独构造并拒绝无效队列参数。 */
    @Test
    fun validatesMetricsWithoutCrashConfiguration() {
        val metrics = MetricsServiceConfig(
            environment = "release",
            channel = "metrics-only",
            fpsEnabled = true,
            memoryEnabled = false,
        )
        assertEquals("metrics-only", metrics.channel)
        assertThrows(IllegalArgumentException::class.java) {
            FpsConfig(maxBatchBytes = 8 * 1024)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemoryConfig(maxAttempts = 0)
        }
    }
}
