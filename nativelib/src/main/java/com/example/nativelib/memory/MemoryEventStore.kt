package com.example.nativelib.memory

import com.example.nativelib.crash.AtomicFileWriter
import com.example.nativelib.network.MemoryMetricEvent
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 内存事件及其本地重试状态。 */
internal data class MemoryQueuedRecord(
    val event: MemoryMetricEvent,
    val createdAtMillis: Long,
    val attempts: Int = 0,
    val nextAttemptAtMillis: Long = 0L,
)

internal data class MemoryQueueItem(
    val record: MemoryQueuedRecord,
    val file: File,
)

/**
 * 内存指标的独立持久队列。
 *
 * 每个事件使用临时文件加原子替换写入，进程重启后直接扫描不可变事件文件恢复。
 */
internal class MemoryEventStore(
    private val root: File,
    private val diskQuotaBytes: Long,
    private val eventTtlMillis: Long,
    private val maxAttempts: Int,
    private val logger: (() -> String) -> Unit = {},
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val eventsDirectory = File(root, EVENTS_DIRECTORY_NAME)
    private val deadLetterDirectory = File(root, DEAD_LETTER_DIRECTORY_NAME)

    init {
        require(root.exists() || root.mkdirs()) { "Unable to create memory queue directory" }
        require(eventsDirectory.exists() || eventsDirectory.mkdirs()) {
            "Unable to create memory event directory"
        }
        require(deadLetterDirectory.exists() || deadLetterDirectory.mkdirs()) {
            "Unable to create memory dead-letter directory"
        }
    }

    @Synchronized
    fun enqueue(event: MemoryMetricEvent, createdAtMillis: Long = clock()): Boolean {
        val target = eventFile(event.eventId)
        if (target.exists()) {
            return false
        }
        AtomicFileWriter.write(
            target,
            gson.toJson(MemoryQueuedRecord(event, createdAtMillis)),
        )
        enforceQuotaLocked()
        return true
    }

    @Synchronized
    fun peekBatch(limit: Int, nowMillis: Long = clock()): List<MemoryQueueItem> {
        require(limit > 0) { "limit must be positive" }
        return listEventFiles()
            .mapNotNull { readRecord(it) }
            .filter { it.record.nextAttemptAtMillis <= nowMillis }
            .sortedWith(compareBy<MemoryQueueItem> { it.record.createdAtMillis }.thenBy {
                it.record.event.eventId
            })
            .take(limit)
    }

    @Synchronized
    fun count(): Int = listEventFiles().size

    @Synchronized
    fun acknowledge(eventIds: Collection<String>) {
        eventIds.forEach { eventId -> eventFile(eventId).takeIf { it.exists() }?.delete() }
    }

    @Synchronized
    fun markRetry(items: Collection<MemoryQueueItem>, nextAttemptAtMillis: Long) {
        items.forEach { item ->
            if (!item.file.exists()) return@forEach
            val updated = item.record.copy(
                attempts = (item.record.attempts + 1).coerceAtMost(maxAttempts),
                nextAttemptAtMillis = nextAttemptAtMillis,
            )
            AtomicFileWriter.write(item.file, gson.toJson(updated))
        }
    }

    @Synchronized
    fun moveToDeadLetter(items: Collection<MemoryQueueItem>, reason: String) {
        if (items.isEmpty()) return
        val safeReason = reason.replace(UNSAFE_REASON_REGEX, "_").take(MAX_REASON_LENGTH)
        items.forEach { item ->
            if (!item.file.exists()) return@forEach
            val target = File(
                deadLetterDirectory,
                "${item.record.event.eventId}-$safeReason-${idGenerator().take(8)}.json",
            )
            if (!item.file.renameTo(target)) {
                logger { "memory dead-letter move failed eventId=${item.record.event.eventId}" }
            }
        }
    }

    @Synchronized
    fun expireOlderThan(nowMillis: Long = clock()) {
        val cutoff = nowMillis - eventTtlMillis
        allQueueFiles()
            .mapNotNull { file -> readRecord(file)?.let { file to it.record } }
            .filter { (_, record) -> record.createdAtMillis < cutoff }
            .forEach { (file, record) ->
                if (file.delete()) {
                    logger { "memory expired event removed eventId=${record.event.eventId}" }
                }
            }
        enforceQuotaLocked()
    }

    private fun enforceQuotaLocked() {
        var totalBytes = allQueueFiles().sumOf { it.length() }
        if (totalBytes <= diskQuotaBytes) return
        // 失败隔离记录优先清理，避免永久错误占满正常事件空间。
        val candidates = deadLetterDirectory.listFiles { file -> file.isFile }
            .orEmpty()
            .sortedBy { it.lastModified() } + listEventFiles().sortedBy { it.lastModified() }
        candidates.forEach { file ->
            if (totalBytes <= diskQuotaBytes) return@forEach
            val length = file.length()
            if (file.delete()) {
                totalBytes -= length
                logger { "memory queue quota removed file=${file.name}" }
            }
        }
    }

    private fun readRecord(file: File): MemoryQueueItem? {
        val record = runCatching {
            gson.fromJson(file.readText(StandardCharsets.UTF_8), MemoryQueuedRecord::class.java)
        }.getOrNull()
        if (record == null || record.event.eventId.isBlank()) {
            moveMalformedFile(file)
            return null
        }
        return MemoryQueueItem(record, file)
    }

    private fun moveMalformedFile(file: File) {
        val target = File(deadLetterDirectory, "malformed-${idGenerator().take(8)}.json")
        if (!file.renameTo(target)) file.delete()
        logger { "memory queue isolated malformed file=${file.name}" }
    }

    private fun listEventFiles(): List<File> = eventsDirectory.listFiles { file ->
        file.isFile && file.extension == EVENT_FILE_EXTENSION
    }.orEmpty().toList()

    private fun allQueueFiles(): List<File> = listEventFiles() +
        deadLetterDirectory.listFiles { file -> file.isFile }.orEmpty().toList()

    private fun eventFile(eventId: String): File {
        require(eventId.matches(EVENT_ID_PATTERN)) { "memory eventId contains unsupported characters" }
        return File(eventsDirectory, "$eventId.$EVENT_FILE_EXTENSION")
    }

    private companion object {
        const val EVENTS_DIRECTORY_NAME = "events"
        const val DEAD_LETTER_DIRECTORY_NAME = "dead-letter"
        const val EVENT_FILE_EXTENSION = "json"
        const val MAX_REASON_LENGTH = 128
        val EVENT_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        val UNSAFE_REASON_REGEX = Regex("[^A-Za-z0-9._-]")
    }
}
