package com.example.nativelib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PerformanceConfigTest {
    @Test
    fun defaultsMatchDocumentedCrashAndJankSettings() {
        val config = PerformanceConfig()

        assertTrue(config.crash.enabled)
        assertEquals(CrashConfig.DEFAULT_BATCH_SIZE, config.crash.batchSize)
        assertEquals(CrashConfig.DEFAULT_MAX_BATCH_BYTES, config.crash.maxBatchBytes)
        assertEquals(CrashConfig.DEFAULT_UPLOAD_INTERVAL_MILLIS, config.crash.uploadIntervalMillis)
        assertTrue(config.jank.enabled)
        assertEquals(JankConfig.DEFAULT_BUFFER_SIZE_BYTES, config.jank.bufferSizeBytes)
        assertEquals(
            JankConfig.DEFAULT_MIN_SAMPLE_INTERVAL_MILLIS,
            config.jank.minSampleIntervalMillis,
        )
        assertEquals(JankConfig.DEFAULT_DISK_QUOTA_BYTES, config.jank.diskQuotaBytes)
        assertEquals(JankConfig.DEFAULT_ARTIFACT_TTL_MILLIS, config.jank.artifactTtlMillis)
        assertEquals(JankConfig.DEFAULT_MAX_ARTIFACT_BYTES, config.jank.maxArtifactBytes)
        assertTrue(config.jank.foregroundOnly)
        assertFalse(config.jank.enableObjectAllocation)
        assertFalse(config.jank.enableStackCaptureStats)
    }

    @Test
    fun customJankSettingsBuildTheRheaConfig() {
        val jank = JankConfig(
            bufferSizeBytes = 2 * 1024 * 1024,
            minSampleIntervalMillis = 5,
            diskQuotaBytes = 8L * 1024L * 1024L,
            artifactTtlMillis = 60_000L,
            maxArtifactBytes = 1L * 1024L * 1024L,
            foregroundOnly = false,
            enableJniHook = true,
            enableObjectAllocation = true,
            enableWakeup = true,
            enableRusage = true,
            enableStackCaptureStats = true,
            mappingId = "mapping-1",
            buildId = "build-override",
        )

        val actual = jank.toOnlineTraceConfig(
            buildId = "build-default",
            anonymousDeviceId = "device-1",
            environment = "staging",
            channel = "beta",
        )

        assertEquals(2 * 1024 * 1024, actual.bufferSizeBytes)
        assertEquals(5_000_000L, actual.minSampleIntervalNs)
        assertEquals(8L * 1024L * 1024L, actual.diskQuotaBytes)
        assertEquals(60_000L, actual.artifactTtlMs)
        assertEquals(1L * 1024L * 1024L, actual.maxArtifactBytes)
        assertFalse(actual.isForegroundOnly)
        assertTrue(actual.isEnableJniHook)
        assertTrue(actual.isEnableObjectAllocation)
        assertTrue(actual.isEnableWakeup)
        assertTrue(actual.isEnableRusage)
        assertTrue(actual.isEnableStackCaptureStats)
        assertEquals("mapping-1", actual.mappingId)
        assertEquals("device-1", actual.anonymousDeviceId)
        assertEquals("build-override", actual.buildId)
        assertEquals("staging", actual.environment)
        assertEquals("beta", actual.channel)
    }

    @Test
    fun invalidPublicLimitsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ServiceConfig(connectTimeoutMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CrashConfig(maxBatchBytes = CrashConfig.MAX_BATCH_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JankConfig(minSampleIntervalMillis = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JankConfig(maxArtifactBytes = JankConfig.MAX_ARTIFACT_BYTES + 1)
        }
    }
}
