package com.shanshui.performance.fps

import com.shanshui.performance.identity.RuntimeIdentity
import com.shanshui.performance.network.FrameSceneSummaryPayload
import com.shanshui.performance.network.FpsMetricEvent
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FPS 当前快照、会话恢复和事件封存测试。 */
class FpsEventStoreTest {
    /** 测试使用的稳定进程身份。 */
    private val testIdentity = RuntimeIdentity(
        sessionId = "11111111-1111-4111-8111-111111111111",
        processId = "22222222-2222-4222-8222-222222222222",
    )

    /** 验证同场景同刷新率区间只生成一个可重试事件。 */
    @Test
    fun mergesSameSceneAndRefreshRateBeforeSealing() {
        val root = Files.createTempDirectory("fps-store-test")
        try {
            val store = createStore(root)
            store.startSession(testIdentity)
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
            first.startSession(testIdentity)
            first.merge(summary("detail", 90.0, 1_000L))
            first.snapshot()

            val second = createStore(root)
            second.startSession(
                RuntimeIdentity(
                    sessionId = "33333333-3333-4333-8333-333333333333",
                    processId = "44444444-4444-4444-8444-444444444444",
                ),
            )

            val pending = second.peekBatch(10)
            assertEquals(1, pending.size)
            assertEquals(testIdentity.sessionId, pending.single().record.event.sessionId)
            assertEquals(testIdentity.processId, pending.single().record.event.processId)
            assertTrue(pending.single().record.event.frameSceneSummary.normalizedFps60 > 0.0)
        } finally {
            deleteRecursively(root)
        }
    }

    /** 验证旧 current.json 缺少 processId 时不伪造身份并继续恢复原任务。 */
    @Test
    fun recoversLegacyCurrentWithoutProcessId() {
        // 测试存储使用的临时目录。
        val root = Files.createTempDirectory("fps-legacy-recover-test")
        try {
            // 先写入带 processId 的新版本快照。
            val first = createStore(root)
            first.startSession(testIdentity)
            first.merge(summary("legacy", 60.0, 1_000L))
            first.snapshot()

            // 删除新增字段，模拟升级前已经写入磁盘的旧快照。
            // 旧 current.json 文件。
            val currentFile = root.resolve("current.json").toFile()
            // 移除 processId 字段后的旧格式 JSON。
            val legacyJson = currentFile.readText().replace(
                "\"processId\":\"${testIdentity.processId}\",",
                "",
            )
            currentFile.writeText(legacyJson)

            // 以新的进程身份恢复旧快照，确认不会覆盖旧任务身份。
            val second = createStore(root)
            second.startSession(
                RuntimeIdentity(
                    sessionId = "33333333-3333-4333-8333-333333333333",
                    processId = "44444444-4444-4444-8444-444444444444",
                ),
            )

            // 从恢复队列中读取的旧事件。
            val recovered = second.peekBatch(10).single().record.event
            assertEquals(testIdentity.sessionId, recovered.sessionId)
            assertEquals("", recovered.processId)
        } finally {
            deleteRecursively(root)
        }
    }

    /** 旧混淆字段、缺失字段及空记录必须隔离原文件，不能阻断新会话或伪造事件。 */
    @Test
    fun isolatesInvalidSnapshotStructureWithoutLosingOriginalFile() {
        // 覆盖旧字段名、缺失列表、空列表元素和缺失记录必填字段。
        val invalidSnapshots = listOf(
            """{"a":"old-session","b":[{"a":"old-event"}]}""",
            """{"sessionId":"old-session"}""",
            """{"sessionId":"old-session","records":[null]}""",
            """{"sessionId":"old-session","records":[{}]}""",
            """{"sessionId":"old-session","records":[{"eventId":"old-event"}]}""",
        )
        invalidSnapshots.forEach { original ->
            // 每种无效结构独立使用临时存储，防止前一轮隔离结果掩盖错误。
            val root = Files.createTempDirectory("fps-invalid-structure")
            try {
                root.resolve("current.json").toFile().writeText(original)
                val store = createStore(root)
                store.startSession(testIdentity)
                assertEquals(0, store.count())
                val isolated = root.resolve("dead-letter").toFile().listFiles()!!.single()
                assertEquals(original, isolated.readText())
                // 新会话仍可正常持久化并封存有效记录。
                store.merge(summary("recovered", 60.0, 1_000L))
                assertEquals(1, store.sealCurrent())
            } finally {
                deleteRecursively(root)
            }
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
