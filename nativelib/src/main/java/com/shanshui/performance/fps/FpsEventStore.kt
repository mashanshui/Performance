package com.shanshui.performance.fps

import com.shanshui.performance.crash.AtomicFileWriter
import com.shanshui.performance.identity.RuntimeIdentity
import com.shanshui.performance.network.FpsMetricEvent
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** 当前运行内按场景和刷新率合并的 FPS 原始聚合记录。 */
internal data class FpsAggregateRecord(
    val eventId: String,
    val sessionId: String,
    /** 旧 current.json 可能没有此字段，因此恢复阶段允许为空并交给服务端拒绝。 */
    val processId: String?,
    val scene: String,
    val algorithmVersion: String,
    val refreshRateHz: Double,
    val activeDurationNs: Long,
    val uiRefreshFrameCount: Long,
    val totalFrameDurationNs: Long,
    val maxFrameDurationNs: Long,
    val callbackDropCount: Long,
    val occurredAtMillis: Long,
    val lastSampleAtMillis: Long,
)

/** 封存队列中带重试状态的 FPS 事件。 */
internal data class FpsQueuedRecord(
    val event: FpsMetricEvent,
    val createdAtMillis: Long,
    val attempts: Int = 0,
    val nextAttemptAtMillis: Long = 0L,
)

/** 队列读取时同时携带事件文件，便于确认、重试和隔离。 */
internal data class FpsQueueItem(
    val record: FpsQueuedRecord,
    val file: File,
)

/** 当前会话快照文件结构。 */
private data class FpsCurrentFile(
    val sessionId: String,
    val records: List<FpsAggregateRecord>,
)

/**
 * FPS 当前会话快照和封存事件队列。
 *
 * 所有公开方法都同步保护内存状态；实际磁盘操作发生在调用方的后台线程，
 * 只有帧回调触发的内存合并会在采集线程快速持锁完成。
 */
internal class FpsEventStore(
    private val root: File,
    private val eventBuilder: (FpsAggregateRecord) -> FpsMetricEvent,
    private val diskQuotaBytes: Long,
    private val eventTtlMillis: Long,
    private val logger: (() -> String) -> Unit = {},
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val eventsDirectory = File(root, EVENTS_DIRECTORY_NAME)
    private val deadLetterDirectory = File(root, DEAD_LETTER_DIRECTORY_NAME)
    private val currentFile = File(root, CURRENT_FILE_NAME)
    private val records = LinkedHashMap<String, FpsAggregateRecord>()
    /** 当前会话 ID，只有 startSession 后才允许合并帧统计。 */
    private var sessionId: String = ""

    /** 当前进程 ID，和当前会话一起写入每个新的聚合桶。 */
    private var processId: String = ""
    private val operationCount = AtomicLong(0L)

    init {
        require(root.exists() || root.mkdirs()) { "Unable to create FPS queue directory" }
        require(eventsDirectory.exists() || eventsDirectory.mkdirs()) {
            "Unable to create FPS events directory"
        }
        require(deadLetterDirectory.exists() || deadLetterDirectory.mkdirs()) {
            "Unable to create FPS dead-letter directory"
        }
    }

    /**
     * 启动一个新会话，并把上次未封存的 current.json 原子转成不可变事件。
     * 新会话使用调用方传入的进程级身份，避免各 Reporter 分别生成 processId。
     */
    @Synchronized
    fun startSession(identity: RuntimeIdentity) {
        require(identity.sessionId.isNotBlank()) { "FPS sessionId must not be blank" }
        require(identity.processId.isNotBlank()) { "FPS processId must not be blank" }
        recoverPreviousSession()
        sessionId = identity.sessionId
        processId = identity.processId
        records.clear()
        persistCurrentLocked()
        logger { "fps store session started; previous current session sealed" }
    }

    /** 将单个页面统计区间合并到当前会话的场景/刷新率桶。 */
    @Synchronized
    fun merge(summary: FpsWindowSummary) {
        require(sessionId.isNotBlank()) { "FPS store session is not started" }
        val key = bucketKey(summary)
        val previous = records[key]
        records[key] = if (previous == null) {
            FpsAggregateRecord(
                eventId = idGenerator(),
                sessionId = sessionId,
                processId = processId,
                scene = summary.scene,
                algorithmVersion = summary.algorithmVersion,
                refreshRateHz = normalizedRefreshRateValue(summary.refreshRateHz),
                activeDurationNs = summary.activeDurationNs,
                uiRefreshFrameCount = summary.uiRefreshFrameCount,
                totalFrameDurationNs = summary.totalFrameDurationNs,
                maxFrameDurationNs = summary.maxFrameDurationNs,
                callbackDropCount = summary.callbackDropCount,
                occurredAtMillis = summary.firstSampleAtMillis,
                lastSampleAtMillis = summary.lastSampleAtMillis,
            )
        } else {
            previous.copy(
                activeDurationNs = saturatingAdd(previous.activeDurationNs, summary.activeDurationNs),
                uiRefreshFrameCount = saturatingAdd(
                    previous.uiRefreshFrameCount,
                    summary.uiRefreshFrameCount,
                ),
                totalFrameDurationNs = saturatingAdd(
                    previous.totalFrameDurationNs,
                    summary.totalFrameDurationNs,
                ),
                maxFrameDurationNs = max(previous.maxFrameDurationNs, summary.maxFrameDurationNs),
                callbackDropCount = saturatingAdd(
                    previous.callbackDropCount,
                    summary.callbackDropCount,
                ),
                occurredAtMillis = minOf(
                    previous.occurredAtMillis,
                    summary.firstSampleAtMillis,
                ),
                lastSampleAtMillis = max(previous.lastSampleAtMillis, summary.lastSampleAtMillis),
            )
        }
        operationCount.incrementAndGet()
    }

    /** 将当前会话快照原子写盘，不改变事件 ID，支持进程异常后的恢复。 */
    @Synchronized
    fun snapshot() {
        if (sessionId.isBlank()) {
            return
        }
        persistCurrentLocked()
        logger { "fps store snapshot saved recordCount=${records.size}" }
    }

    /** 封存当前会话的所有桶；重复调用不会生成新的 eventId。 */
    @Synchronized
    fun sealCurrent(): Int {
        if (records.isEmpty()) {
            persistCurrentLocked()
            return 0
        }
        var sealed = 0
        records.values.forEach { aggregate ->
            val event = eventBuilder(aggregate)
            val target = eventFile(event.eventId)
            if (!target.exists()) {
                AtomicFileWriter.write(
                    target,
                    gson.toJson(FpsQueuedRecord(event, clock())),
                )
                sealed += 1
            } else {
                logger { "fps event already sealed eventId=${event.eventId}; keep existing file" }
            }
        }
        records.clear()
        persistCurrentLocked()
        enforceQuotaLocked()
        logger { "fps store sealed records=$sealed" }
        return sealed
    }

    /** 返回下一批到期可发送的 FPS 事件。 */
    @Synchronized
    fun peekBatch(limit: Int, nowMillis: Long = clock()): List<FpsQueueItem> {
        require(limit > 0) { "limit must be positive" }
        return listEventFiles()
            .mapNotNull { file -> readQueuedRecord(file) }
            .filter { it.record.nextAttemptAtMillis <= nowMillis }
            .sortedWith(compareBy<FpsQueueItem> { it.record.createdAtMillis }.thenBy {
                it.record.event.eventId
            })
            .take(limit)
    }

    /** 返回当前封存事件数量。 */
    @Synchronized
    fun count(): Int = listEventFiles().size

    /** 删除已被服务端 accepted 或 duplicate 确认的事件。 */
    @Synchronized
    fun acknowledge(eventIds: Collection<String>) {
        eventIds.forEach { eventId ->
            eventFile(eventId).takeIf { it.exists() }?.delete()
        }
        logger { "fps queue acknowledged count=${eventIds.size}" }
    }

    /** 更新失败事件的尝试次数和下次重试时间，保留原始 eventId。 */
    @Synchronized
    fun markRetry(items: Collection<FpsQueueItem>, nextAttemptAtMillis: Long) {
        items.forEach { item ->
            if (!item.file.exists()) {
                return@forEach
            }
            val updated = item.record.copy(
                attempts = (item.record.attempts + 1).coerceAtMost(MAX_ATTEMPTS),
                nextAttemptAtMillis = nextAttemptAtMillis,
            )
            AtomicFileWriter.write(item.file, gson.toJson(updated))
        }
        logger { "fps queue retry scheduled count=${items.size}" }
    }

    /** 把永久失败或损坏事件移动到 dead-letter，避免阻塞合法指标。 */
    @Synchronized
    fun moveToDeadLetter(items: Collection<FpsQueueItem>, reason: String) {
        val safeReason = reason.replace(UNSAFE_REASON_REGEX, "_").take(MAX_REASON_LENGTH)
        items.forEach { item ->
            if (!item.file.exists()) {
                return@forEach
            }
            val target = File(
                deadLetterDirectory,
                "${item.record.event.eventId}-$safeReason-${idGenerator().take(8)}.json",
            )
            if (!item.file.renameTo(target)) {
                logger { "fps dead-letter move failed eventId=${item.record.event.eventId}" }
            }
        }
        logger { "fps queue isolated permanent events count=${items.size} reason=$safeReason" }
    }

    /** 清理超过保留期的封存事件，避免长期离线导致队列无限增长。 */
    @Synchronized
    fun expireOlderThan(nowMillis: Long = clock()) {
        val cutoff = nowMillis - eventTtlMillis
        listEventFiles().forEach { file ->
            val record = readQueuedRecord(file)?.record ?: return@forEach
            if (record.createdAtMillis < cutoff) {
                file.delete()
                logger { "fps expired event removed eventId=${record.event.eventId}" }
            }
        }
    }

    /** 返回用于测试和诊断的内存合并操作计数，不进入任何上报载荷。 */
    @Synchronized
    fun mergeOperationCount(): Long = operationCount.get()

    /** 读取并封存上次异常终止留下的 current.json。 */
    private fun recoverPreviousSession() {
        if (!currentFile.isFile) {
            return
        }
        val current = runCatching {
            gson.fromJson(currentFile.readText(StandardCharsets.UTF_8), FpsCurrentFile::class.java)
        }.getOrNull()
        if (current == null) {
            moveMalformedCurrent()
            return
        }
        current.records.forEach { aggregate ->
            val event = eventBuilder(aggregate)
            val target = eventFile(event.eventId)
            if (!target.exists()) {
                AtomicFileWriter.write(target, gson.toJson(FpsQueuedRecord(event, clock())))
            }
        }
        AtomicFileWriter.write(currentFile, gson.toJson(FpsCurrentFile("", emptyList())))
        enforceQuotaLocked()
        logger { "fps previous current.json recovered records=${current.records.size}" }
    }

    /** 将当前记录移动到 dead-letter，保证损坏快照不会阻止新会话启动。 */
    private fun moveMalformedCurrent() {
        val target = File(deadLetterDirectory, "current-corrupt-${idGenerator().take(8)}.json")
        if (!currentFile.renameTo(target)) {
            currentFile.delete()
        }
        logger { "fps current.json was malformed and isolated" }
    }

    /** 在锁内持久化 current.json。 */
    private fun persistCurrentLocked() {
        AtomicFileWriter.write(
            currentFile,
            gson.toJson(FpsCurrentFile(sessionId, records.values.toList())),
        )
    }

    /** 根据事件大小删除最旧封存事件，保留当前快照和最新队列。 */
    private fun enforceQuotaLocked() {
        var totalBytes = listEventFiles().sumOf { it.length() }
        if (totalBytes <= diskQuotaBytes) {
            return
        }
        listEventFiles()
            .sortedBy { it.lastModified() }
            .forEach { file ->
                if (totalBytes <= diskQuotaBytes) {
                    return@forEach
                }
                val length = file.length()
                if (file.delete()) {
                    totalBytes -= length
                    logger { "fps queue quota removed oldest file=${file.name}" }
                }
            }
    }

    /** 列出事件目录中的 JSON 文件。 */
    private fun listEventFiles(): List<File> {
        return eventsDirectory.listFiles { file ->
            file.isFile && file.extension == EVENT_FILE_EXTENSION
        }.orEmpty().toList()
    }

    /** 安全读取单个封存事件，损坏文件会被忽略并在下一次刷新时隔离。 */
    private fun readQueuedRecord(file: File): FpsQueueItem? {
        val record = runCatching {
            gson.fromJson(file.readText(StandardCharsets.UTF_8), FpsQueuedRecord::class.java)
        }.getOrNull()
        if (record == null || record.event.eventId.isBlank()) {
            logger { "fps queue ignored malformed file=${file.name}" }
            return null
        }
        return FpsQueueItem(record, file)
    }

    /** 根据事件 ID定位队列文件，并限制文件名字符集。 */
    private fun eventFile(eventId: String): File {
        require(eventId.matches(EVENT_ID_PATTERN)) { "FPS eventId contains unsupported characters" }
        return File(eventsDirectory, "$eventId.$EVENT_FILE_EXTENSION")
    }

    /** 返回稳定的场景/算法/刷新率合并键。 */
    private fun bucketKey(summary: FpsWindowSummary): String {
        return buildString {
            append(summary.scene)
            append('\u0000')
            append(summary.algorithmVersion)
            append('\u0000')
            append(normalizedRefreshRate(summary.refreshRateHz))
        }
    }

    /** 将设备返回的微小刷新率抖动归并到同一个刷新率桶。 */
    private fun normalizedRefreshRate(value: Double): String {
        return "%.3f".format(java.util.Locale.US, value)
    }

    /** 将刷新率文本桶还原成事件中使用的稳定 Double 值。 */
    private fun normalizedRefreshRateValue(value: Double): Double {
        return normalizedRefreshRate(value).toDouble()
    }

    /** 在 Long 溢出时饱和相加，保证持久化字段始终非负。 */
    private fun saturatingAdd(first: Long, second: Long): Long {
        if (second <= 0L) {
            return first
        }
        return if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second
    }

    private companion object {
        const val EVENTS_DIRECTORY_NAME = "events"
        const val DEAD_LETTER_DIRECTORY_NAME = "dead-letter"
        const val CURRENT_FILE_NAME = "current.json"
        const val EVENT_FILE_EXTENSION = "json"
        const val MAX_ATTEMPTS = 1_000
        const val MAX_REASON_LENGTH = 128
        val EVENT_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        val UNSAFE_REASON_REGEX = Regex("[^A-Za-z0-9._-]")
    }
}
