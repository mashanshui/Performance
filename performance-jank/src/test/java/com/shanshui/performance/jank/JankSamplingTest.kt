package com.shanshui.performance.jank

import com.bytedance.rheatrace.RheaTrace3
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 验证异步配置契约与导出摘要，避免初始化中和空产物被误报。 */
class JankSamplingTest {
    @get:Rule val temp = TemporaryFolder()

    private fun convert(config: JankConfig) = config.toOnlineTraceConfig(
        "build", "device", "12345678-1234-4234-8234-123456789abc", "test", "local")

    @Test fun defaultsAndAsyncConfigurationArePreserved() {
        val defaults = convert(JankConfig())
        assertTrue(defaults.isEnableSyncSampling)
        assertFalse(defaults.isEnableAsyncSampling)
        val async = convert(JankConfig(enableSyncSampling = false, enableAsyncSampling = true))
        assertFalse(async.isEnableSyncSampling)
        assertTrue(async.isEnableAsyncSampling)
        assertEquals(50_000_000L, async.asyncSampleIntervalNs)
        listOf(10L, 60_000L).forEach { convert(JankConfig(asyncSampleIntervalMillis = it)) }
    }

    @Test fun rejectsInvalidIntervalsAndAllowsDisabledAsyncBelowMinimum() {
        listOf(9L, 60_001L).forEach { interval ->
            assertThrows(IllegalArgumentException::class.java) { JankConfig(asyncSampleIntervalMillis = interval) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            JankConfig(enableAsyncSampling = true, minSampleIntervalMillis = 100L)
        }
        convert(JankConfig(enableAsyncSampling = false, minSampleIntervalMillis = 100L))
    }

    @Test fun mapsEverySamplingAndRequestState() {
        RheaTrace3.SamplingStatus.values().forEach {
            assertEquals(it.name, JankDiagnostics.mapSamplingStatus(it).name)
        }
        RheaTrace3.ExportRequestResult.values().forEach {
            assertEquals(if (it == RheaTrace3.ExportRequestResult.INVALID_JANK_METADATA) "INVALID_EVENT" else it.name,
                JankHandle.mapExportRequest(it).name)
        }
    }

    private fun summary(status: RheaTrace3.ExportStatus, file: File?, count: Int) =
        JankArtifactReporter.summarizeExport(RheaTrace3.ExportResult(
            status, file, null, null, null, count, 7L, 0L, ""))

    @Test fun validExportRetainsSummaryAfterFileDeletion() {
        val file = temp.newFile("sample.zip").apply { writeText("zip-content") }
        val result = summary(RheaTrace3.ExportStatus.PARTIAL, file, 2)
        file.delete()
        assertEquals(JankExportStatus.PARTIAL, result.status)
        assertEquals(file.absolutePath, result.artifactPath)
        assertEquals(11L, result.artifactSizeBytes)
        assertEquals(7L, result.overwrittenRecordCount)
        assertFalse(result.queued)
    }

    @Test fun emptyMissingAndFailedArtifactsCannotBeEnqueued() {
        val file = temp.newFile("empty.zip")
        listOf(
            summary(RheaTrace3.ExportStatus.SUCCESS, file, 2),
            summary(RheaTrace3.ExportStatus.FAILED, null, 2),
            summary(RheaTrace3.ExportStatus.EMPTY, null, 0),
            summary(RheaTrace3.ExportStatus.SUCCESS, file.apply { writeText("data") }, 0),
        ).forEach {
            assertNull(it.artifactPath)
            assertFalse(it.queued)
            assertEquals(0L, it.artifactSizeBytes)
            assertTrue(it.status == JankExportStatus.EMPTY || it.status == JankExportStatus.FAILED)
        }
        assertEquals(JankExportStatus.SUCCESS, summary(RheaTrace3.ExportStatus.SUCCESS, file, 2).status)
    }
}
