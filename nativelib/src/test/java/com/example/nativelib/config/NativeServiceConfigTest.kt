package com.example.nativelib.config

import com.example.nativelib.CrashConfig
import com.example.nativelib.JankConfig
import com.example.nativelib.PerformanceConfig
import com.example.nativelib.ServiceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeServiceConfigTest {
    @Test
    fun toStringRedactsAppKey() {
        val config = PerformanceConfig().toNativeServiceConfig("secret-app-key")

        val text = config.toString()

        assertTrue(text.contains("appKey=<redacted>"))
        assertFalse(text.contains("secret-app-key"))
    }

    @Test
    fun defaultsMapToSharedInternalServiceConfig() {
        val config = PerformanceConfig().toNativeServiceConfig("test-app-key")

        assertEquals(ServiceConfig.DEFAULT_BASE_URL, config.baseUrl)
        assertEquals(ServiceConfig.DEFAULT_ENVIRONMENT, config.environment)
        assertEquals(ServiceConfig.DEFAULT_CHANNEL, config.channel)
        assertEquals(CrashConfig.DEFAULT_BATCH_SIZE, config.crashBatchSize)
        assertEquals(CrashConfig.DEFAULT_MAX_BATCH_BYTES, config.crashMaxBatchBytes)
        assertEquals(JankConfig.DEFAULT_MAX_ARTIFACT_BYTES, config.jankMaxArtifactBytes)
        assertTrue(config.crashEnabled)
        assertTrue(config.jankEnabled)
    }

    @Test
    fun customNestedValuesMapWithoutLeakingAppKey() {
        val config = PerformanceConfig(
            service = ServiceConfig(
                baseUrl = "https://example.test/api",
                environment = "staging",
                channel = "beta",
                connectTimeoutMillis = 1_000L,
            ),
            crash = CrashConfig(
                enabled = false,
                batchSize = 7,
                maxBatchBytes = 64 * 1024,
                uploadIntervalMillis = 5_000L,
            ),
            jank = JankConfig(
                enabled = false,
                maxArtifactBytes = 2L * 1024L * 1024L,
                uploadIntervalMillis = 6_000L,
            ),
        )

        val actual = config.toNativeServiceConfig("secret-app-key")

        assertEquals("https://example.test/api", actual.baseUrl)
        assertEquals("staging", actual.environment)
        assertEquals("beta", actual.channel)
        assertEquals(1_000L, actual.connectTimeoutMillis)
        assertEquals(7, actual.crashBatchSize)
        assertEquals(64 * 1024, actual.crashMaxBatchBytes)
        assertEquals(5_000L, actual.crashUploadIntervalMillis)
        assertEquals(6_000L, actual.jankUploadIntervalMillis)
        assertFalse(actual.crashEnabled)
        assertFalse(actual.jankEnabled)
    }

    @Test
    fun invalidValuesAreRejectedBeforeReporterInitialization() {
        assertThrows(IllegalArgumentException::class.java) {
            PerformanceConfig(service = ServiceConfig(baseUrl = "not-a-url"))
                .toNativeServiceConfig("key")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PerformanceConfig(crash = CrashConfig(batchSize = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PerformanceConfig(jank = JankConfig(maxArtifactBytes = JankConfig.MAX_ARTIFACT_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PerformanceConfig().toNativeServiceConfig(" ")
        }
    }
}
