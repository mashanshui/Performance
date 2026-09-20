package com.example.nativelib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PerformanceConfigTest {
    /** 验证默认 FPS、Crash 和 Jank 配置与文档保持一致。 */
    @Test
    fun defaultsMatchDocumentedCrashAndJankSettings() {
        val config = PerformanceConfig()

        assertTrue(config.crash.enabled)
        assertEquals(ServiceConfig.DEFAULT_SCHEMA_VERSION, config.service.schemaVersion)
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
        assertTrue(config.jank.fps.enabled)
        assertEquals(FpsLogLevel.OFF, config.jank.fps.logLevel)
        assertEquals(
            FpsConfig.DEFAULT_SNAPSHOT_INTERVAL_MILLIS,
            config.jank.fps.snapshotIntervalMillis,
        )
        assertTrue(config.memoryLeak.enabled)
        assertEquals(
            MemoryLeakConfig.DEFAULT_FOREGROUND_SCAN_INTERVAL_MILLIS,
            config.memoryLeak.foregroundScanIntervalMillis,
        )
        assertEquals(
            MemoryLeakConfig.DEFAULT_BACKGROUND_SCAN_INTERVAL_MILLIS,
            config.memoryLeak.backgroundScanIntervalMillis,
        )
        assertEquals(MemoryLeakConfig.DEFAULT_MAX_RECHECK_COUNT, config.memoryLeak.maxRecheckCount)
        assertEquals(MemoryLeakConfig.DEFAULT_GC_DELAY_MILLIS, config.memoryLeak.gcDelayMillis)
        assertTrue(config.memoryLeak.skipWhenDebuggerConnected)

        // 指标采集和 Activity 泄漏检测是两个独立开关。
        val independentConfig = PerformanceConfig(
            memory = MemoryConfig(enabled = false),
            memoryLeak = MemoryLeakConfig(enabled = true),
        )
        assertFalse(independentConfig.memory.enabled)
        assertTrue(independentConfig.memoryLeak.enabled)
    }

    /** 验证自定义 Jank 参数仍能转换为 Rhea 配置。 */
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
            processId = "11111111-1111-4111-8111-111111111111",
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
        assertEquals("11111111-1111-4111-8111-111111111111", actual.processId)
        assertEquals("build-override", actual.buildId)
        assertEquals("staging", actual.environment)
        assertEquals("beta", actual.channel)
    }

    /** 验证非法 FPS 边界在 reporter 初始化前被拒绝。 */
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
        assertThrows(IllegalArgumentException::class.java) {
            JankConfig(fps = FpsConfig(maxBatchBytes = FpsConfig.MAX_BATCH_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemoryLeakConfig(foregroundScanIntervalMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemoryLeakConfig(
                backgroundScanIntervalMillis = MemoryLeakConfig.MAX_SCAN_INTERVAL_MILLIS + 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemoryLeakConfig(maxRecheckCount = MemoryLeakConfig.MAX_RECHECK_COUNT + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemoryLeakConfig(gcDelayMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemoryLeakConfig(gcDelayMillis = MemoryLeakConfig.MAX_GC_DELAY_MILLIS + 1)
        }
    }
}
