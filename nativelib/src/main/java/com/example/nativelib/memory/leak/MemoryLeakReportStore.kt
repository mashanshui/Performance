package com.example.nativelib.memory.leak

import com.example.nativelib.crash.AtomicFileWriter
import com.example.nativelib.network.MemoryLeakReportMetadata
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 内存泄漏报告上传链路使用的固定边界，暂不暴露为公共 SDK 配置。 */
internal object MemoryLeakReportLimits {
    const val MAX_REPORT_BYTES = 2L * 1024L * 1024L
    const val QUEUE_DISK_QUOTA_BYTES = 20L * 1024L * 1024L
    const val EVENT_TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    const val MAX_ATTEMPTS = 10
    const val UPLOAD_INTERVAL_MILLIS = 30_000L
    const val BASE_RETRY_DELAY_MILLIS = 30_000L
    const val MAX_RETRY_DELAY_MILLIS = 60L * 60L * 1_000L
    const val MAX_BACKOFF_EXPONENT = 7
}

/** 队列中的内存泄漏报告记录；report 内容单独作为 JSON 文件保存。 */
internal data class MemoryLeakReportQueuedRecord(
    val metadata: MemoryLeakReportMetadata,
    val createdAtMillis: Long,
    val attempts: Int = 0,
    val nextAttemptAtMillis: Long = 0L,
)

internal data class MemoryLeakReportQueueItem(
    val record: MemoryLeakReportQueuedRecord,
    val eventDirectory: File,
    val reportFile: File,
)

/**
 * 内存泄漏报告的持久队列。
 *
 * metadata 和 report 通过临时文件、flush/sync 与原子重命名写入，进程退出后可恢复未确认任务。
 */
internal class MemoryLeakReportStore(
    private val root: File,
    private val diskQuotaBytes: Long = MemoryLeakReportLimits.QUEUE_DISK_QUOTA_BYTES,
    private val logger: (() -> String) -> Unit = {},
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val eventsDirectory = File(root, EVENTS_DIRECTORY_NAME)
    private val deadLetterDirectory = File(root, DEAD_LETTER_DIRECTORY_NAME)

    init {
        require(diskQuotaBytes > 0) { "memory leak report disk quota must be positive" }
        require(root.exists() || root.mkdirs()) {
            "Unable to create memory leak report queue directory"
        }
        require(eventsDirectory.exists() || eventsDirectory.mkdirs()) {
            "Unable to create memory leak report event directory"
        }
        require(deadLetterDirectory.exists() || deadLetterDirectory.mkdirs()) {
            "Unable to create memory leak report dead-letter directory"
        }
    }

    /** 先保存 report，再保存记录；记录存在即表示该任务可以恢复。 */
    @Synchronized
    fun enqueue(
        metadata: MemoryLeakReportMetadata,
        reportContent: String,
        createdAtMillis: Long = metadata.occurredAt,
    ): Boolean {
        val eventId = metadata.eventId
        require(eventId.matches(EVENT_ID_PATTERN)) {
            "memory leak report eventId contains unsupported file characters"
        }
        val reportBytes = reportContent.toByteArray(StandardCharsets.UTF_8)
        if (reportBytes.isEmpty() || reportBytes.size.toLong() > MemoryLeakReportLimits.MAX_REPORT_BYTES) {
            logger {
                "memory leak report rejected locally eventId=$eventId size=${reportBytes.size}"
            }
            return false
        }

        val eventDirectory = eventDirectory(eventId)
        if (eventDirectory.exists()) return false
        require(eventDirectory.mkdirs()) {
            "Unable to create memory leak report event directory"
        }

        val reportFile = File(eventDirectory, REPORT_FILE_NAME)
        val recordFile = File(eventDirectory, RECORD_FILE_NAME)
        try {
            AtomicFileWriter.write(reportFile, reportContent)
            AtomicFileWriter.write(
                recordFile,
                gson.toJson(
                    MemoryLeakReportQueuedRecord(
                        metadata = metadata,
                        createdAtMillis = createdAtMillis,
                    ),
                ),
            )
            enforceQuotaLocked()
            return true
        } catch (throwable: Throwable) {
            eventDirectory.deleteRecursively()
            throw throwable
        }
    }

    @Synchronized
    fun nextReady(nowMillis: Long): MemoryLeakReportQueueItem? {
        return readAll()
            .filter { it.record.nextAttemptAtMillis <= nowMillis }
            .minWithOrNull(
                compareBy<MemoryLeakReportQueueItem> { it.record.createdAtMillis }
                    .thenBy { it.record.metadata.eventId },
            )
    }

    @Synchronized
    fun count(): Int = readAll().size

    @Synchronized
    fun nextAttemptAtMillis(): Long? = readAll().minOfOrNull { it.record.nextAttemptAtMillis }

    @Synchronized
    fun markRetry(item: MemoryLeakReportQueueItem, nextAttemptAtMillis: Long) {
        val recordFile = File(item.eventDirectory, RECORD_FILE_NAME)
        if (!recordFile.exists()) return
        AtomicFileWriter.write(
            recordFile,
            gson.toJson(
                item.record.copy(
                    attempts = (item.record.attempts + 1)
                        .coerceAtMost(MemoryLeakReportLimits.MAX_ATTEMPTS),
                    nextAttemptAtMillis = nextAttemptAtMillis,
                ),
            ),
        )
    }

    @Synchronized
    fun acknowledge(eventId: String) {
        eventDirectory(eventId).takeIf { it.exists() }?.deleteRecursively()
    }

    @Synchronized
    fun moveToDeadLetter(item: MemoryLeakReportQueueItem, reason: String) {
        if (!item.eventDirectory.exists()) return
        val safeReason = reason.replace(UNSAFE_REASON_REGEX, "_").take(MAX_REASON_LENGTH)
        val target = File(
            deadLetterDirectory,
            "${item.record.metadata.eventId}-$safeReason-${idGenerator().take(8)}",
        )
        if (!item.eventDirectory.renameTo(target)) {
            logger {
                "memory leak report dead-letter move failed " +
                    "eventId=${item.record.metadata.eventId} reason=$safeReason"
            }
        }
    }

    @Synchronized
    fun expireOlderThan(cutoffMillis: Long) {
        readAll()
            .filter { it.record.createdAtMillis < cutoffMillis }
            .forEach { item ->
                if (item.eventDirectory.deleteRecursively()) {
                    logger {
                        "memory leak report expired eventId=${item.record.metadata.eventId}"
                    }
                }
            }
        deadLetterDirectory
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .forEach { directory ->
                val createdAtMillis = runCatching {
                    gson.fromJson(
                        File(directory, RECORD_FILE_NAME).readText(StandardCharsets.UTF_8),
                        MemoryLeakReportQueuedRecord::class.java,
                    )?.createdAtMillis
                }.getOrNull()
                if (createdAtMillis != null && createdAtMillis < cutoffMillis &&
                    directory.deleteRecursively()
                ) {
                    logger { "memory leak dead-letter expired directory=${directory.name}" }
                }
            }
        enforceQuotaLocked()
    }

    private fun readAll(): List<MemoryLeakReportQueueItem> {
        return eventsDirectory
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull { directory ->
                val item = readItem(directory)
                if (item == null) {
                    moveMalformedDirectory(directory)
                }
                item
            }
    }

    private fun readItem(directory: File): MemoryLeakReportQueueItem? {
        val recordFile = File(directory, RECORD_FILE_NAME)
        val reportFile = File(directory, REPORT_FILE_NAME)
        return runCatching {
            val record = gson.fromJson(
                recordFile.readText(StandardCharsets.UTF_8),
                MemoryLeakReportQueuedRecord::class.java,
            )
            requireNotNull(record)
            require(directory.name.matches(EVENT_ID_PATTERN))
            require(record.metadata.eventId == directory.name)
            require(record.createdAtMillis >= 0L)
            require(record.attempts >= 0)
            require(record.nextAttemptAtMillis >= 0L)
            require(reportFile.isFile)
            require(reportFile.length() <= MemoryLeakReportLimits.MAX_REPORT_BYTES)
            MemoryLeakReportQueueItem(record, directory, reportFile)
        }.getOrNull()
    }

    private fun moveMalformedDirectory(directory: File) {
        val target = File(
            deadLetterDirectory,
            "malformed-${idGenerator().take(8)}",
        )
        if (!directory.renameTo(target)) {
            directory.deleteRecursively()
        }
        logger { "memory leak report malformed queue isolated directory=${directory.name}" }
    }

    private fun enforceQuotaLocked() {
        var totalBytes = allQueueDirectories().sumOf(::directorySize)
        if (totalBytes <= diskQuotaBytes) return

        val candidates = deadLetterDirectory.listFiles { file -> file.isDirectory }
            .orEmpty()
            .sortedBy { it.lastModified() } +
            eventsDirectory.listFiles { file -> file.isDirectory }
                .orEmpty()
                .sortedBy { it.lastModified() }
        candidates.forEach { directory ->
            if (totalBytes <= diskQuotaBytes) return@forEach
            val length = directorySize(directory)
            if (directory.deleteRecursively()) {
                totalBytes -= length
                logger { "memory leak report queue quota removed directory=${directory.name}" }
            }
        }
    }

    private fun allQueueDirectories(): List<File> {
        return eventsDirectory.listFiles { file -> file.isDirectory }.orEmpty().toList() +
            deadLetterDirectory.listFiles { file -> file.isDirectory }.orEmpty().toList()
    }

    private fun directorySize(directory: File): Long {
        return directory.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    private fun eventDirectory(eventId: String): File {
        require(eventId.matches(EVENT_ID_PATTERN)) {
            "memory leak report eventId contains unsupported file characters"
        }
        return File(eventsDirectory, eventId)
    }

    private companion object {
        const val EVENTS_DIRECTORY_NAME = "events"
        const val DEAD_LETTER_DIRECTORY_NAME = "dead-letter"
        const val RECORD_FILE_NAME = "record.json"
        const val REPORT_FILE_NAME = "report.json"
        const val MAX_REASON_LENGTH = 128
        val EVENT_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val UNSAFE_REASON_REGEX = Regex("[^A-Za-z0-9._-]")
    }
}
