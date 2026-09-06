package com.example.nativelib.fps

import com.example.nativelib.network.FrameSceneSummaryPayload
import com.example.nativelib.network.FpsMetricEvent
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FPS 当前快照、会话恢复和事件封存测试。 */
class FpsEventStoreTest {
    /** 验证同场景同刷新率区间只生成一个可重试事件。 */
    @Test
    fun mergesSameSceneAndRefreshRateBeforeSealing() {
        val root = Files.createTempDirectory("fps-store-test")
        try {
            val store = createStore(root)
            store.startSession("session-1")
            store.merge(summary("home", 60.0, 1_000L))
            store.merge(summary("home", 60.0, 2_000L))

            assertEquals(0, store.count())
            assertEquals(1, store.sealCurrent())
            assertEquals(1, store.count())
            assertEquals(1, store.peekBatch(10).size)
            assertEquals(1_000L, store.peekBatch(10).single().record.event.occurredAt)
        } finally {
            deleteRecursively(root)
        }
    }

    /** 验证异常终止留下的 current.json 会在下一次启动封存并保留 eventId。 */
    @Test
    fun recoversPreviousCurrentSession() {
        val root = Files.createTempDirectory("fps-recover-test")
        try {
            val first = createStore(root)
            first.startSession("session-old")
            first.merge(summary("detail", 90.0, 1_000L))
            first.snapshot()

            val second = createStore(root)
            second.startSession("session-new")

            val pending = second.peekBatch(10)
            assertEquals(1, pending.size)
            assertEquals("session-old", pending.single().record.event.sessionId)
            assertTrue(pending.single().record.event.frameSceneSummary.normalizedFps60 > 0.0)
        } finally {
            deleteRecursively(root)
        }
    }

    /** 创建使用固定事件载荷的测试存储。 */
    private fun createStore(root: Path): FpsEventStore {
        return FpsEventStore(
            root = root.toFile(),
            eventBuilder = { aggregate ->
                val raw = aggregate.uiRefreshFrameCount * 1_000_000_000.0 /
                    aggregate.activeDurationNs.coerceAtLeast(1L)
                FpsMetricEvent(
                    schemaVersion = 2,
                    eventId = aggregate.eventId,
                    eventType = "frame_scene_summary",
                    occurredAt = aggregate.occurredAtMillis,
                    sessionId = aggregate.sessionId,
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
                        activeDurationMs = aggregate.activeDurationNs / 1_000_000L,
                        uiRefreshFrameCount = aggregate.uiRefreshFrameCount,
                        refreshRateHz = aggregate.refreshRateHz,
                        normalizedFps60 = raw * 60.0 / aggregate.refreshRateHz,
                    ),
                )
            },
            diskQuotaBytes = 2L * 1024L * 1024L,
            eventTtlMillis = 7L * 24L * 60L * 60L * 1_000L,
        )
    }

    /** 创建一个最小合法的页面统计快照。 */
    private fun summary(scene: String, refreshRateHz: Double, sampleAt: Long): FpsWindowSummary {
        return FpsWindowSummary(
            scene = scene,
            algorithmVersion = "fps-v1",
            refreshRateHz = refreshRateHz,
            activeDurationNs = 16_666_666L,
            uiRefreshFrameCount = 1L,
            totalFrameDurationNs = 16_666_666L,
            maxFrameDurationNs = 16_666_666L,
            callbackDropCount = 0L,
            firstSampleAtMillis = sampleAt,
            lastSampleAtMillis = sampleAt,
        )
    }

    /** 递归删除本测试创建的临时目录。 */
    private fun deleteRecursively(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
