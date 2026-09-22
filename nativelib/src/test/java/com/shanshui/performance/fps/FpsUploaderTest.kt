package com.shanshui.performance.fps

import com.shanshui.performance.identity.RuntimeIdentity
import com.shanshui.performance.network.FpsBatchResponse
import com.shanshui.performance.network.FrameSceneSummaryPayload
import com.shanshui.performance.network.FpsMetricEvent
import com.shanshui.performance.network.NetworkResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** FPS 批次响应确认和不完整响应保留测试。 */
class FpsUploaderTest {
    /** 测试使用的稳定进程身份。 */
    private val testIdentity = RuntimeIdentity(
        sessionId = "11111111-1111-4111-8111-111111111111",
        processId = "22222222-2222-4222-8222-222222222222",
    )

    /** 验证 duplicate 与 accepted 一样可以删除本地事件。 */
    @Test
    fun duplicateResponseAcknowledgesEvent() = runBlocking {
        val root = Files.createTempDirectory("fps-uploader-test")
        try {
            val store = createStore(root)
            store.startSession(testIdentity)
            store.merge(summary())
            store.sealCurrent()
            val uploader = FpsUploader(
                store = store,
                sender = FpsBatchSender {
                    NetworkResult.Success(
                        FpsBatchResponse(
                            requestId = it.requestId,
                            duplicate = 1,
                        ),
                        200,
                    )
                },
                config = com.shanshui.performance.FpsConfig(),
            )

            uploader.flush()

            assertEquals(0, store.count())
        } finally {
            deleteRecursively(root)
        }
    }

    /** 验证响应计数不完整时保留事件，等待后续重试而不是误删。 */
    @Test
    fun invalidResponseCountKeepsEvent() = runBlocking {
        val root = Files.createTempDirectory("fps-uploader-invalid-test")
        try {
            val store = createStore(root)
            store.startSession(testIdentity)
            store.merge(summary())
            store.sealCurrent()
            val uploader = FpsUploader(
                store = store,
                sender = FpsBatchSender {
                    NetworkResult.Success(FpsBatchResponse(requestId = it.requestId), 200)
                },
                config = com.shanshui.performance.FpsConfig(),
                clock = { 1_000L },
            )

            uploader.flush()

            assertEquals(1, store.count())
        } finally {
            deleteRecursively(root)
        }
    }

    /** 创建测试用 FPS 存储和最小协议载荷。 */
    private fun createStore(root: Path): FpsEventStore {
        return FpsEventStore(
            root = root.toFile(),
            eventBuilder = { aggregate ->
                FpsMetricEvent(
                    schemaVersion = 2,
                    eventId = aggregate.eventId,
                    eventType = "frame_scene_summary",
                    occurredAt = aggregate.occurredAtMillis,
                    sessionId = aggregate.sessionId,
                    processId = aggregate.processId.orEmpty(),
                    anonymousDeviceId = "device",
                    packageName = "com.example.test",
                    appVersion = "1.0",
                    versionCode = 1,
                    buildId = "build",
                    environment = "test",
                    channel = "unit",
                    osVersion = "test",
                    deviceModel = "test",
                    frameSceneSummary = FrameSceneSummaryPayload(
                        scene = aggregate.scene,
                        algorithmVersion = aggregate.algorithmVersion,
                        activeDurationMs = 17L,
                        uiRefreshFrameCount = aggregate.uiRefreshFrameCount,
                        refreshRateHz = aggregate.refreshRateHz,
                        normalizedFps60 = 60.0,
                    ),
                )
            },
            diskQuotaBytes = 2L * 1024L * 1024L,
            eventTtlMillis = 7L * 24L * 60L * 60L * 1_000L,
        )
    }

    /** 创建一条最小合法 FPS 统计快照。 */
    private fun summary(): FpsWindowSummary {
        return FpsWindowSummary(
            scene = "home",
            algorithmVersion = "fps-v1",
            refreshRateHz = 60.0,
            activeDurationNs = 16_666_666L,
            uiRefreshFrameCount = 1L,
            totalFrameDurationNs = 16_666_666L,
            maxFrameDurationNs = 16_666_666L,
            callbackDropCount = 0L,
            firstSampleAtMillis = 1_000L,
            lastSampleAtMillis = 1_000L,
        )
    }

    /** 递归删除测试创建的临时队列目录。 */
    private fun deleteRecursively(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
