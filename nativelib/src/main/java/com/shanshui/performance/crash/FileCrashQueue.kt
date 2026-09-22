package com.shanshui.performance.crash

import com.shanshui.performance.network.CrashEvent
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

internal data class CrashQueueRecord(
    val event: CrashEvent,
    val createdAtMillis: Long,
    val attempts: Int = 0,
    val nextAttemptAtMillis: Long = 0,
)

internal data class CrashQueueItem(
    val record: CrashQueueRecord,
    val file: File,
)

internal class FileCrashQueue(
    private val root: File,
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
    private val maxDeadLetters: Int = 100,
) {
    private val eventsDirectory = File(root, EVENTS_DIRECTORY_NAME)
    private val deadLetterDirectory = File(root, DEAD_LETTER_DIRECTORY_NAME)

    init {
        require(root.exists() || root.mkdirs()) { "Unable to create crash queue directory" }
        require(eventsDirectory.exists() || eventsDirectory.mkdirs()) {
            "Unable to create crash event directory"
        }
        require(deadLetterDirectory.exists() || deadLetterDirectory.mkdirs()) {
            "Unable to create crash dead-letter directory"
        }
    }

    @Synchronized
    fun enqueue(event: CrashEvent, createdAtMillis: Long = System.currentTimeMillis()): Boolean {
        val target = eventFile(event.eventId)
        if (target.exists()) {
            return false
        }
        val record = CrashQueueRecord(
            event = event,
            createdAtMillis = createdAtMillis,
        )
        AtomicFileWriter.write(target, gson.toJson(record))
        return true
    }

    @Synchronized
    fun peekBatch(
        limit: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<CrashQueueItem> {
        require(limit > 0) { "limit must be positive" }
        return eventsDirectory
            .listFiles { file -> file.isFile && file.extension == EVENT_FILE_EXTENSION }
            .orEmpty()
            .mapNotNull { file ->
                val record = readRecord(file)
                if (record == null) {
                    moveMalformedFile(file)
                    null
                } else {
                    CrashQueueItem(record, file)
                }
            }
            .filter { it.record.nextAttemptAtMillis <= nowMillis }
            .sortedWith(
                compareBy<CrashQueueItem> { it.record.createdAtMillis }
                    .thenBy { it.record.event.eventId },
            )
            .take(limit)
    }

    @Synchronized
    fun count(): Int {
        return eventsDirectory
            .listFiles { file -> file.isFile && file.extension == EVENT_FILE_EXTENSION }
            ?.size
            ?: 0
    }

    @Synchronized
    fun acknowledge(eventIds: Collection<String>) {
        eventIds.forEach { eventId ->
            eventFile(eventId).takeIf { it.exists() }?.delete()
        }
    }

    @Synchronized
    fun markRetry(items: Collection<CrashQueueItem>, nextAttemptAtMillis: Long) {
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
    }

    @Synchronized
    fun moveToDeadLetter(items: Collection<CrashQueueItem>, reason: String) {
        if (items.isEmpty()) {
            return
        }
        val deadLetter = File(
            deadLetterDirectory,
            "${System.currentTimeMillis()}-${UUID.randomUUID()}.$DEAD_LETTER_EXTENSION",
        )
        val payload = DeadLetterRecord(
            movedAtMillis = System.currentTimeMillis(),
            reason = CrashSanitizer.sanitize(reason, MAX_REASON_LENGTH),
            records = items.map { it.record },
        )
        AtomicFileWriter.write(deadLetter, gson.toJson(payload))
        acknowledge(items.map { it.record.event.eventId })
        trimDeadLetters()
    }

    @Synchronized
    fun expireOlderThan(cutoffMillis: Long) {
        val expired = peekAll().filter { it.record.createdAtMillis < cutoffMillis }
        moveToDeadLetter(expired, "event_expired")
    }

    private fun peekAll(): List<CrashQueueItem> {
        return eventsDirectory
            .listFiles { file -> file.isFile && file.extension == EVENT_FILE_EXTENSION }
            .orEmpty()
            .mapNotNull { file ->
                val record = readRecord(file)
                if (record == null) {
                    moveMalformedFile(file)
                    null
                } else {
                    CrashQueueItem(record, file)
                }
            }
    }

    private fun readRecord(file: File): CrashQueueRecord? {
        return runCatching {
            val record = gson.fromJson(
                file.readText(StandardCharsets.UTF_8),
                CrashQueueRecord::class.java,
            )
            requireNotNull(record)
            require(record.event.eventId.matches(EVENT_ID_PATTERN))
            require(file.nameWithoutExtension == record.event.eventId)
            record
        }.getOrNull()
    }

    private fun moveMalformedFile(file: File) {
        val diagnostic = File(
            deadLetterDirectory,
            "${System.currentTimeMillis()}-${UUID.randomUUID()}-malformed.$DEAD_LETTER_EXTENSION",
        )
        AtomicFileWriter.write(
            diagnostic,
            gson.toJson(
                mapOf(
                    "movedAtMillis" to System.currentTimeMillis(),
                    "reason" to "malformed_queue_record",
                ),
            ),
        )
        file.delete()
        trimDeadLetters()
    }

    private fun trimDeadLetters() {
        val files = deadLetterDirectory
            .listFiles { file -> file.isFile && file.extension == DEAD_LETTER_EXTENSION }
            .orEmpty()
            .sortedBy { it.lastModified() }
        files.take((files.size - maxDeadLetters).coerceAtLeast(0)).forEach { it.delete() }
    }

    private fun eventFile(eventId: String): File {
        require(eventId.matches(EVENT_ID_PATTERN)) { "eventId contains unsupported file characters" }
        return File(eventsDirectory, "$eventId.$EVENT_FILE_EXTENSION")
    }

    private data class DeadLetterRecord(
        val movedAtMillis: Long,
        val reason: String,
        val records: List<CrashQueueRecord>,
    )

    private companion object {
        const val EVENTS_DIRECTORY_NAME = "events"
        const val DEAD_LETTER_DIRECTORY_NAME = "dead-letter"
        const val EVENT_FILE_EXTENSION = "json"
        const val DEAD_LETTER_EXTENSION = "json"
        const val MAX_ATTEMPTS = 1_000
        const val MAX_REASON_LENGTH = 256
        val EVENT_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
    }
}

internal class DeviceIdentityStore(
    private val file: File,
) {
    @Synchronized
    fun getOrCreate(): String {
        val existing = runCatching {
            file.takeIf { it.isFile }?.readText(StandardCharsets.UTF_8)?.trim()
        }.getOrNull()
        if (!existing.isNullOrBlank() && existing.length <= 256) {
            return existing
        }
        val generated = UUID.randomUUID().toString()
        AtomicFileWriter.write(file, generated)
        return generated
    }
}

internal object AtomicFileWriter {
    fun write(target: File, content: String) {
        target.parentFile?.let { parent ->
            require(parent.exists() || parent.mkdirs()) { "Unable to create parent directory" }
        }
        val temporary = File(target.parentFile, "${target.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                // Android's rename is atomic on the same filesystem. Some JVM test
                // filesystems reject replacing an existing file, so retry after removal.
                if (!target.delete() || !temporary.renameTo(target)) {
                    throw IOException("Unable to atomically replace ${target.name}")
                }
            }
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }
}
