package com.shanshui.performance.memory

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.shanshui.performance.metrics.MemoryConfig
import com.shanshui.performance.network.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 共同契约、独立累计、可靠替换、跨轮封存及确认的确定性回归。 */
class MemoryRuntimePeaksTest {
    /** 共同样例直接读取外部检出唯一源，字段漂移即使客户端往返自洽也会失败。 */
    @Test fun sharedFixtureRoundTripHasOnlyPeakPayload() {
        /** 共同契约在外部配套检出中的首选路径。 */
        val fixture = File("../../IdeaProjects/apm-server/docs/fixtures/memory-runtime-peaks.json")
        /** 当前工作目录下可读取的唯一共同样例。 */
        val source = if (fixture.exists()) fixture else File("../../../IdeaProjects/apm-server/docs/fixtures/memory-runtime-peaks.json")
        assertTrue("需要配套 apm-server 检出", source.isFile)
        /** 用于检查字段类型的 JSON 树。 */
        val json = JsonParser.parseString(source.readText())
        /** 本轮原身份及独立峰值的完整信封。 */
        val event = Gson().fromJson(json, MemoryMetricEvent::class.java)
        assertEquals(json, Gson().toJsonTree(event))
        assertEquals(4, UUID.fromString(event.eventId).version())
        assertEquals(4, UUID.fromString(event.processId).version())
        assertEquals(event.processId, event.sessionId)
        assertEquals(event.occurredAt, event.memoryPeakSummary.lastSampledAt)
        assertFalse(json.asJsonObject.has("memorySample"))
        assertEquals(200L, event.memoryPeakSummary.pss!!.bytes)
        assertTrue(Gson().toJsonTree(event).asJsonObject["memoryPeakSummary"].asJsonObject["pss"].asJsonObject["bytes"].toString().matches(Regex("[0-9]+")))
        /** 只缺失一项的合法运行汇总。 */
        val nullable = event.copy(memoryPeakSummary = event.memoryPeakSummary.copy(pss = null))
        assertFalse(Gson().toJsonTree(nullable).asJsonObject["memoryPeakSummary"].asJsonObject.has("pss"))
        /** 显式 null 与省略均可反序列化，剩余项仍保持独立峰值。 */
        val withNull = Gson().toJsonTree(nullable).asJsonObject
        withNull["memoryPeakSummary"].asJsonObject.add("pss", com.google.gson.JsonNull.INSTANCE)
        assertNull(Gson().fromJson(withNull, MemoryMetricEvent::class.java).memoryPeakSummary.pss)
    }

    /** 每项独立最大值，等值保留首次上下文；回退墙钟不打乱时间。 */
    @Test fun independentPeaksEqualZeroMissingAndClockRollback() {
        /** 只在内存比较三项独立峰值的累计器。 */
        val accumulator = MemoryPeakAccumulator(event().copy(memoryPeakSummary = MemoryPeakSummary("test", 100, 100)))
        assertFalse(accumulator.observe(MemorySampleValues(null, null, null), 101, true, "A"))
        assertEquals(100L, accumulator.event.occurredAt)
        assertTrue(accumulator.observe(MemorySampleValues(100, 500, 20), 110, false, "ignored"))
        assertTrue(accumulator.observe(MemorySampleValues(200, 400, 80), 120, true, "B"))
        assertFalse(accumulator.observe(MemorySampleValues(150, 450, 40), 130, true, "C"))
        assertFalse(accumulator.observe(MemorySampleValues(200, null, 80), 90, false, null))
        /** 原运行区间与三项独立峰值。 */
        val summary = accumulator.event.memoryPeakSummary
        assertEquals(MemoryPeak(200, 120, true, "B"), summary.pss)
        assertEquals(MemoryPeak(500, 110, false), summary.vss)
        assertEquals(MemoryPeak(80, 120, true, "B"), summary.javaHeap)
        assertEquals(130L, summary.lastSampledAt)
        /** 专门验证真实零值及缺失分支的累计器。 */
        val zero = MemoryPeakAccumulator(event().copy(memoryPeakSummary = MemoryPeakSummary("test", 0, 0)))
        assertTrue(zero.observe(MemorySampleValues(0, null, null), 0, true, null))
        assertFalse(zero.observe(MemorySampleValues(null, null, null), 1, false, null))
        assertFalse(zero.observe(MemorySampleValues(-1, MemorySampler.MAX_MEMORY_BYTES + 1, null), 2, false, null))
        assertEquals(0L, zero.event.memoryPeakSummary.pss!!.bytes)
        assertEquals(0L, zero.event.occurredAt)
    }

    /** 每个替换失败点均恢复旧完整版本，半份临时文件不参与读取。 */
    @Test fun atomicWriteSyncReplaceAndCommitFailuresKeepOldSnapshot() = temporary { root ->
        /** 本次文件操作的目标路径。 */
        val target = File(root, "current.json")
        for (stage in listOf("write", "sync", "replace", "commit")) {
            MemoryAtomicFile().write(target, "old")
            expectFailure { MemoryAtomicFile { if (it == stage) throw IOException(stage) }.write(target, "new") }
            File(root, "current.json.new").writeText("{")
            assertEquals("old", MemoryAtomicFile().read(target))
        }
        File(root, "current.json.bak").writeText("backup")
        target.writeText("{")
        assertEquals("backup", MemoryAtomicFile().read(target))
    }

    /** 同进程恢复原版本，检查点不追加事件；不同进程才封存一次。 */
    @Test fun twoRunsKeepCurrentPrivateAndUploadOriginalMetadata() = temporary { root -> runBlocking {
        /** 受测内存快照和封存存储。 */
        val store = store(root)
        /** 必须保留原身份和元数据的旧轮事件。 */
        val original = event()
        /** 首次创建的受测实例。 */
        val first = MemoryRunRecorder(store, PROCESS) { original.copy(memoryPeakSummary = MemoryPeakSummary("test", 100, 100)) }
        first.observe(MemorySampleValues(100, 500, 20), 110, true, "A")
        first.observe(MemorySampleValues(200, 400, 80), 120, true, "B")
        first.observe(MemorySampleValues(150, 450, 40), 130, false, null)
        assertEquals(120L, store.currentEvent()!!.occurredAt)
        first.checkpoint()
        assertEquals(130L, store.currentEvent()!!.occurredAt)
        /** 记录实际发送的原事件，确认前不得改写。 */
        var sent: MemoryMetricEvent? = null
        /** 使用可控响应和时钟的历史上传器。 */
        val uploader = MemoryUploader(store, MemoryBatchSender {
            sent = it.events.single()
            NetworkResult.Success(MemoryBatchResponse(requestId = it.requestId, accepted = 1), 200)
        }, MemoryConfig(), clock = { 200 })
        repeat(3) { uploader.flush() }
        assertNull(sent)
        /** 恢复原运行状态的受测实例。 */
        val restored = MemoryRunRecorder(store(root), PROCESS) { error("同轮不得重建元数据") }
        restored.start()
        assertEquals(0, store.count())
        /** 封存前固定的完整事件，用于逐字段比较。 */
        val sealed = store.currentEvent()!!
        /** 接管或恢复后的第二个受测实例。 */
        val second = MemoryRunRecorder(store(root), NEXT_PROCESS) {
            event().copy(eventId = "44444444-4444-4444-8444-444444444444", processId = NEXT_PROCESS, sessionId = NEXT_PROCESS, appVersion = "2", buildId = "new", memoryPeakSummary = MemoryPeakSummary("test", 200, 200), occurredAt = 200)
        }
        second.start()
        assertEquals(1, store.count())
        second.observe(MemorySampleValues(1, 1, 1), 200, false, null)
        uploader.flush()
        assertEquals(sealed, sent)
        assertEquals(original.eventId, sent!!.eventId)
        assertEquals("old-build", sent!!.buildId)
        assertEquals("1", sent!!.appVersion)
        assertEquals(0, store.count())
        assertEquals(NEXT_PROCESS, store.currentEvent()!!.processId)
    } }

    /** 已封存后 current 删除失败可幂等接管，不能覆盖重试状态。 */
    @Test fun interruptedSealReusesExistingRecordAndPreservesRetries() = temporary { root ->
        /** 故障发生前已有可靠状态的存储。 */
        val initial = store(root)
        initial.saveCurrent(event())
        /** 在指定文件边界注入失败的存储。 */
        val failing = store(root, atomic = MemoryAtomicFile { if (it == "remove") throw IOException("crash") })
        expectFailure { failing.restore(NEXT_PROCESS) }
        assertNotNull(initial.currentEvent())
        assertEquals(1, initial.count())
        assertTrue(initial.peekBatch(10).isEmpty())
        assertNull(initial.restore(NEXT_PROCESS))
        /** 已读取的原事件和重试状态。 */
        val item = initial.peekBatch(1).single()
        initial.markRetry(listOf(item), 500)
        initial.saveCurrent(event()) // 重放封存后/新 current 前的中断状态。
        assertNull(store(root).restore(NEXT_PROCESS))
        assertTrue(initial.peekBatch(1, 499).isEmpty())
        assertEquals(1, initial.peekBatch(1, 500).single().record.attempts)
    }

    /** 封存写失败/配额不足保留唯一 current，成功恢复后才可开始新轮。 */
    @Test fun sealFailureAndQuotaDoNotOverwriteCurrent() = temporary { root ->
        store(root).saveCurrent(event())
        /** 在指定文件边界注入失败的存储。 */
        val failing = store(root, atomic = MemoryAtomicFile { if (it == "sync") throw IOException("disk") })
        expectFailure { failing.restore(NEXT_PROCESS) }
        assertEquals(event(), store(root).currentEvent())
        expectFailure { store(root, quota = File(root, "current.json").length() + 1).restore(NEXT_PROCESS) }
        assertEquals(event(), store(root).currentEvent())
        assertEquals(0, store(root).count())
        assertNull(store(root).restore(NEXT_PROCESS))
        assertEquals(1, store(root).count())
    }

    /** 损坏和旧样本隔离，同 ID 冲突不会覆盖有效 current。 */
    @Test fun malformedLegacyAndConflictingRecordsAreIsolated() = temporary { root ->
        /** 受测内存快照和封存存储。 */
        val store = store(root)
        store.saveCurrent(event())
        store.enqueue(event().copy(buildId = "conflict"))
        store.restore(NEXT_PROCESS)
        assertEquals("old-build", store.peekBatch(1).single().record.event.buildId)
        File(root, "events/legacy.json").writeText("""{"event":{"eventType":"memory_sample","eventId":"legacy"}}""")
        File(root, "current.json").writeText("{")
        assertNull(store.restore(NEXT_PROCESS))
        assertEquals(1, store.peekBatch(10).size)
        assertTrue(File(root, "dead-letter").listFiles()!!.size >= 3)
    }

    /** 按原截止计算 8 天仍保留/90 天恰好过期，活动快照不被 TTL 清理。 */
    @Test fun ttlUsesOriginalEndAndDoesNotRenewOrDeleteActiveCurrent() = temporary { root ->
        /** 一天对应的毫秒数，用于精确 TTL 边界。 */
        val day = 86_400_000L
        /** 受测内存快照和封存存储。 */
        val store = store(root, now = 100 + 8 * day)
        store.saveCurrent(event())
        store.expireOlderThan(100 + 91 * day)
        assertNotNull(store.currentEvent())
        store.restore(NEXT_PROCESS)
        assertEquals(1, store.peekBatch(1, 100 + 8 * day).size)
        store.expireOlderThan(100 + 90 * day)
        assertEquals(0, store.count())
        store.saveCurrent(event())
        assertNull(store(root, now = 100 + 90 * day).restore(NEXT_PROCESS))
        assertEquals(0, store.count())
    }

    /** 空采样不创建文件，写失败的新高保留到检查点重试。 */
    @Test fun pendingPeakRetriesAndCheckpointAdvancesEndWithoutQueueing() = temporary { root ->
        /** 故障注入开关，关闭后允许可靠重试。 */
        var fail = true
        /** 受测内存快照和封存存储。 */
        val store = store(root, atomic = MemoryAtomicFile { if (fail && it == "write") throw IOException("disk") })
        /** 驱动恢复、累计和检查点的本轮状态机。 */
        val recorder = MemoryRunRecorder(store, PROCESS) { event().copy(memoryPeakSummary = MemoryPeakSummary("test", 100, 100)) }
        recorder.observe(MemorySampleValues(null, null, null), 101, true, null)
        assertNull(store.currentEvent())
        expectFailure { recorder.observe(MemorySampleValues(200, null, null), 110, true, "A") }
        fail = false
        recorder.checkpoint()
        recorder.observe(MemorySampleValues(100, null, null), 120, false, null)
        assertEquals(110L, store.currentEvent()!!.occurredAt)
        recorder.checkpoint()
        assertEquals(120L, store.currentEvent()!!.occurredAt)
        assertEquals(MemoryPeak(200, 110, true, "A"), store.currentEvent()!!.memoryPeakSummary.pss)
        assertEquals(0, store.count())
    }

    /** 超时/解析/限流均保留原事件，flush 不绕过退避，duplicate 才清理。 */
    @Test fun transientFailuresAndLostResponsesKeepPayloadUntilDuplicate() = temporary { root -> runBlocking {
        /** 所有需要保留原事件的暂时失败响应。 */
        val failures = listOf<NetworkResult<MemoryBatchResponse>>(
            NetworkResult.HttpError(408, null, true), NetworkResult.HttpError(429, null, true, 30),
            NetworkResult.HttpError(503, null, true, 30), NetworkResult.NetworkError(IOException("lost response")),
            NetworkResult.SerializationError(IOException("invalid json")),
            NetworkResult.Success(MemoryBatchResponse(requestId = null, accepted = 1), 200),
        )
        for ((index, failure) in failures.withIndex()) {
            /** 当前用例独立的持久目录。 */
            val directory = File(root, index.toString())
            /** 受测内存快照和封存存储。 */
            val store = store(directory)
            store.enqueue(event())
            /** 本次调度或测试使用的可控时刻。 */
            var now = 200L
            /** 记录实际发送的原事件，确认前不得改写。 */
            val sent = mutableListOf<MemoryMetricEvent>()
            /** 使用可控响应和时钟的历史上传器。 */
            val uploader = MemoryUploader(store, MemoryBatchSender { request ->
                sent += request.events.single()
                if (sent.size == 1) failure else NetworkResult.Success(MemoryBatchResponse(requestId = request.requestId, duplicate = 1), 200)
            }, MemoryConfig(), clock = { now }, random = { 0 })
            uploader.flush()
            assertEquals(1, store.count())
            uploader.flush()
            assertEquals(1, sent.size)
            now = 100_000
            uploader.flush()
            assertEquals(listOf(event(), event()), sent)
            assertEquals(0, store.count())
        }
    } }

    /** 缺少必需字段、伪造零值或浮点时间不可恢复为完整快照。 */
    @Test fun incompletePeakObjectsAreIsolatedInsteadOfCoercedToZero() = temporary { root ->
        for (field in listOf("bytes", "foreground", "occurredAt")) {
            /** 当前用例独立的持久目录。 */
            val directory = File(root, field).apply { mkdirs() }
            /** 用于检查字段类型的 JSON 树。 */
            val json = Gson().toJsonTree(event()).asJsonObject
            json["memoryPeakSummary"].asJsonObject["pss"].asJsonObject.remove(field)
            File(directory, "current.json").writeText(json.toString())
            assertNull(store(directory).restore(PROCESS))
            assertEquals(1, File(directory, "dead-letter").listFiles()!!.size)
        }
    }

    /** 成功及失败均带稳定 ID，日志不泄露信封元数据或 JSON 正文。 */
    @Test fun persistenceLogsCorrelateFailureAndSuccessWithoutPayload() = temporary { root ->
        /** 收集脱敏日志，不使用 Android Log。 */
        val logs = mutableListOf<String>()
        /** 同一候选先失败后成功，验证不会误报首次落盘成功。 */
        var fail = true
        /** 注入一次写失败，其余配置与生产一致。 */
        val store = MemoryEventStore(root, 2_000_000, MemoryConfig.DEFAULT_EVENT_TTL_MILLIS, 10,
            logger = { logs += it() }, clock = { 200 }, atomic = MemoryAtomicFile { if (fail && it == "write") throw IOException("disk") })
        expectFailure { store.saveCurrent(event()) }
        assertTrue(logs.any { it.contains("save failed eventId=$EVENT") })
        assertFalse(logs.any { it.contains("current saved") })
        fail = false
        store.saveCurrent(event())
        store.restore(NEXT_PROCESS)
        store.acknowledge(listOf(EVENT))
        assertTrue(logs.any { it.contains("current saved eventId=$EVENT") })
        assertTrue(logs.any { it.contains("run sealed eventId=$EVENT") })
        assertTrue(logs.any { it.contains("acknowledged eventId=$EVENT") })
        assertFalse(logs.any { it.contains("old-build") || it.contains("memoryPeakSummary") })
    }

    /** 新 current 首次写失败时，已封存旧轮仍在；重试只提交同一个新候选。 */
    @Test fun newCurrentFailureAfterSealKeepsBothRunBoundaries() = temporary { root ->
        /** 原轮已有可靠 current。 */
        val initial = store(root)
        initial.saveCurrent(event())
        initial.restore(NEXT_PROCESS)
        /** 下一轮第一次持久化模拟磁盘失败。 */
        var fail = true
        /** 旧历史和新 current 使用相同配额及文件语义。 */
        val recovering = store(root, atomic = MemoryAtomicFile { if (fail && it == "write") throw IOException("new current") })
        /** 新候选固定一次，不因重试而重新生成 ID。 */
        val next = event().copy(eventId = "44444444-4444-4444-8444-444444444444", processId = NEXT_PROCESS, sessionId = NEXT_PROCESS,
            memoryPeakSummary = MemoryPeakSummary("test", 200, 200), occurredAt = 200)
        /** 新运行在旧轮封存后开始采样。 */
        val recorder = MemoryRunRecorder(recovering, NEXT_PROCESS) { next }
        expectFailure { recorder.observe(MemorySampleValues(10, null, null), 200, true, null) }
        assertNull(initial.currentEvent())
        assertEquals(event(), initial.peekBatch(1).single().record.event)
        fail = false
        recorder.checkpoint()
        assertEquals(next.eventId, initial.currentEvent()!!.eventId)
        assertEquals(1, initial.count())
        initial.acknowledge(listOf(EVENT))
        assertEquals(next.eventId, initial.currentEvent()!!.eventId)
    }

    /** 构造和页面读取数量不能创建目录；计数仅由后台状态变化发布。 */
    @Test fun queueCountIsPublishedWithoutCallerFilesystemWork() = temporary { root ->
        /** 尚未使用的独立路径，不能由构造器或 count 创建。 */
        val directory = File(root, "deferred")
        /** 同目录不同实例共享后台维护的可见计数。 */
        val first = store(directory)
        /** 模拟下一代 Reporter 的诊断读取。 */
        val second = store(directory)
        assertEquals(0, first.count())
        assertFalse(directory.exists())
        first.enqueue(event())
        assertEquals(1, second.count())
        second.acknowledge(listOf(EVENT))
        assertEquals(0, first.count())
    }

    /** 不完整重试状态和文件身份不符不能变成永远无法确认删除的上传任务。 */
    @Test fun malformedRetryStateAndMismatchedFilenameAreIsolated() = temporary { root ->
        /** 正常封存事件是故障样本的唯一来源。 */
        val store = store(root)
        store.enqueue(event())
        /** 损坏本地 wrapper，禁止 Gson 默认 attempts=0 让其续命。 */
        val recordFile = File(root, "events/$EVENT.json")
        /** 保留完整旧信封，仅删除必需重试字段。 */
        val json = JsonParser.parseString(recordFile.readText()).asJsonObject
        json.remove("attempts")
        recordFile.writeText(json.toString())
        assertTrue(store.peekBatch(10).isEmpty())
        store.enqueue(event())
        assertTrue(recordFile.renameTo(File(root, "events/wrong-id.json")))
        assertTrue(store.peekBatch(10).isEmpty())
        /** 长损坏文件名仍应成功隔离，目标文件名必须受限。 */
        val longName = File(root, "events/${"x".repeat(200)}.json")
        longName.writeText("{")
        assertTrue(store.peekBatch(10).isEmpty())
        assertEquals(0, store.count())
        assertEquals(3, File(root, "dead-letter").listFiles()!!.size)
    }

    /** 恢复时已达到当前上限的任务直接隔离，不能先多发一次。 */
    @Test fun exhaustedRecoveredRecordDoesNotAttemptNetwork() = temporary { root -> runBlocking {
        /** 先以较高上限建立可靠事件和既有失败次数。 */
        val store = store(root)
        store.enqueue(event())
        store.markRetry(store.peekBatch(1), 0)
        /** 配置收紧后上传器必须在网络调用前识别耗尽。 */
        val uploader = MemoryUploader(store, MemoryBatchSender { error("exhausted record sent") }, MemoryConfig(maxAttempts = 1), clock = { 200 })
        uploader.flush()
        assertEquals(0, store.count())
        assertEquals(1, File(root, "dead-letter").listFiles()!!.size)
    } }

    /** 非法或缩写身份不能覆盖最后可靠快照。 */
    @Test fun snapshotRejectsNonV4AndMismatchedRuntimeIdentity() = temporary { root ->
        /** 先建立原身份正确的完整快照。 */
        val store = store(root)
        store.saveCurrent(event())
        for (invalid in listOf(
            event().copy(eventId = "ordinary-sample-id"),
            event().copy(processId = "11111111-1111-1111-8111-111111111111"),
            event().copy(processId = "1-1-4001-8001-1", sessionId = "1-1-4001-8001-1"),
            event().copy(sessionId = NEXT_PROCESS),
        )) expectFailure { store.saveCurrent(invalid) }
        assertEquals(event(), store.currentEvent())
    }

    /** 旧请求在 SDK 接管后才失败时，不能覆盖新失败次数或缩短既有退避。 */
    @Test fun staleInFlightRetryKeepsLiveAttemptCountAndBackoff() = temporary { root ->
        /** 初始任务与两次请求发送前取得的同一个旧版本。 */
        val store = store(root)
        store.enqueue(event())
        /** 模拟旧、新上传器同时持有发送前副本。 */
        val stale = store.peekBatch(1).single()
        store.markRetry(listOf(stale), 500)
        store.markRetry(listOf(stale), 300)
        assertTrue(store.peekBatch(1, 499).isEmpty())
        assertEquals(2, store.peekBatch(1, 500).single().record.attempts)
        assertEquals(event(), store.peekBatch(1, 500).single().record.event)
        store.acknowledge(listOf(EVENT))
        store.markRetry(listOf(stale), 1_000)
        assertEquals(0, store.count())
    }

    /** 并发旧副本到达真正尝试上限时必须立即隔离，不等待下一次网络发送。 */
    @Test fun staleInFlightRetryExhaustsAtPersistedLimit() = temporary { root ->
        /** 使用小上限精确覆盖第二次失败的终态。 */
        val store = MemoryEventStore(root, 2_000_000, MemoryConfig.DEFAULT_EVENT_TTL_MILLIS, 2, clock = { 200 })
        store.enqueue(event())
        /** 两个在途请求都从零次失败的同一版本出发。 */
        val stale = store.peekBatch(1).single()
        assertEquals(0, store.markRetry(listOf(stale), 500))
        assertEquals(1, store.markRetry(listOf(stale), 300))
        assertEquals(0, store.count())
        assertEquals(1, File(root, "dead-letter").listFiles()!!.size)
    }

    /** 批次选择按原入队顺序且遵守退避，完整对象数量受单批上限约束。 */
    @Test fun boundedBatchSelectionKeepsOldestEligibleRecords() = temporary { root ->
        /** 多于两个最大批次的合成历史，不来自真实设备。 */
        val store = store(root)
        for (index in 119 downTo 0) {
            store.enqueue(event().copy(eventId = "00000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}"), index.toLong())
        }
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), store.peekBatch(5).map { it.record.createdAtMillis })
        store.markRetry(store.peekBatch(1), 1_000)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), store.peekBatch(5, 200).map { it.record.createdAtMillis })
        assertEquals(50, store.peekBatch(50).size)
        expectFailure { store.peekBatch(51) }
    }

    /** 当前轮保护不跨目录清理，配额先清隔离。 */
    @Test fun quotaIncludesCurrentAndDeadLetterButNeverOtherDirectories() = temporary { root ->
        /** 不属于内存指标的相邻目录，清理不得越界。 */
        val other = File(root, "../other-${root.name}").apply { mkdirs() }
        try {
            File(other, "keep").writeText("keep")
            /** 故障发生前已有可靠状态的存储。 */
            val initial = store(root)
            initial.saveCurrent(event())
            File(root, "dead-letter/old.json").writeText("x".repeat(5000))
            /** 使用小配额验证清理顺序的存储。 */
            val limited = store(root, quota = File(root, "current.json").length() + 10)
            limited.expireOlderThan(200)
            assertTrue(File(root, "dead-letter").listFiles()!!.isEmpty())
            assertNotNull(limited.currentEvent())
            assertEquals("keep", File(other, "keep").readText())
        } finally { other.deleteRecursively() }
    }

    /** 安全的统一原运行事件。 */
    private fun event(): MemoryMetricEvent = MemoryMetricEvent(2, EVENT, "memory_peak_summary", 100, PROCESS, PROCESS,
        "device", "test", "1", 1, "old-build", "test", "unit", "35", "test", memoryPeakSummary = MemoryPeakSummary("test", 100, 100, pss = MemoryPeak(0, 100, true)))

    /** 用可控时钟和故障边界构造内存独立存储。 */
    private fun store(root: File, now: Long = 200, quota: Long = 2_000_000, atomic: MemoryAtomicFile = MemoryAtomicFile()): MemoryEventStore =
        MemoryEventStore(root, quota, MemoryConfig.DEFAULT_EVENT_TTL_MILLIS, 10, clock = { now }, atomic = atomic)

    /** 每个文件实验拥有独立临时目录。 */
    private fun temporary(block: (File) -> Unit) {
        /** 本例使用的内存指标独立目录。 */
        val root = Files.createTempDirectory("memory-peaks").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    /** 要求故障确实发生，避免测试错误通过。 */
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("expected failure") } catch (expected: Exception) { assertNotNull(expected.message) }
    }

    private companion object {
        /** 共同形态的稳定 UUID v4。 */ const val PROCESS = "11111111-1111-4111-8111-111111111111"
        /** 下一次进程身份。 */ const val NEXT_PROCESS = "33333333-3333-4333-8333-333333333333"
        /** 首个有效快照固定一次。 */ const val EVENT = "22222222-2222-4222-8222-222222222222"
    }
}
