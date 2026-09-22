package com.shanshui.performance.jank

import com.shanshui.performance.crash.AtomicFileWriter
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.charset.StandardCharsets

internal data class JankUploadRecord(
    val eventId: String,
    val fileName: String,
    val createdAtMillis: Long,
    val attempts: Int = 0,
    val nextAttemptAtMillis: Long = 0,
    val deletePending: Boolean = false,
)

internal data class JankQueueItem(
    val record: JankUploadRecord,
    val metadataFile: File,
)

internal class FileJankUploadQueue(
    private val root: File,
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) {
    init {
        require(root.exists() || root.mkdirs()) { "Unable to create jank upload queue" }
    }

    @Synchronized
    fun reconcile(
        pendingArtifacts: Collection<File>,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val pendingByName = pendingArtifacts
            .filter { artifact -> validArtifactName(artifact.name) }
            .associateBy { artifact -> artifact.name }

        readAll().forEach { item ->
            if (item.record.fileName !in pendingByName) {
                item.metadataFile.delete()
            }
        }
        pendingByName.values.forEach { artifact ->
            enqueue(artifact, artifact.lastModified().takeIf { it > 0 } ?: nowMillis)
        }
    }

    @Synchronized
    fun enqueue(
        artifact: File,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        require(validArtifactName(artifact.name)) { "artifact file name is invalid" }
        val eventId = eventIdFromFileName(artifact.name)
        val target = recordFile(eventId)
        if (target.exists()) {
            return false
        }
        val record = JankUploadRecord(
            eventId = eventId,
            fileName = artifact.name,
            createdAtMillis = createdAtMillis,
        )
        AtomicFileWriter.write(target, gson.toJson(record))
        return true
    }

    @Synchronized
    fun nextReady(nowMillis: Long = System.currentTimeMillis()): JankQueueItem? {
        return readAll()
            .filter { item -> item.record.nextAttemptAtMillis <= nowMillis }
            .minWithOrNull(
                compareBy<JankQueueItem> { item -> item.record.createdAtMillis }
                    .thenBy { item -> item.record.eventId },
            )
    }

    @Synchronized
    fun count(): Int = readAll().size

    @Synchronized
    fun find(eventId: String): JankQueueItem? {
        return readAll().firstOrNull { item -> item.record.eventId == eventId }
    }

    @Synchronized
    fun nextAttemptAtMillis(): Long? {
        return readAll().minOfOrNull { item -> item.record.nextAttemptAtMillis }
    }

    @Synchronized
    fun markRetry(item: JankQueueItem, nextAttemptAtMillis: Long) {
        if (!item.metadataFile.exists()) {
            return
        }
        val updated = item.record.copy(
            attempts = (item.record.attempts + 1).coerceAtMost(MAX_ATTEMPTS),
            nextAttemptAtMillis = nextAttemptAtMillis,
        )
        AtomicFileWriter.write(item.metadataFile, gson.toJson(updated))
    }

    @Synchronized
    fun markDeletePending(item: JankQueueItem) {
        if (!item.metadataFile.exists()) {
            return
        }
        val updated = item.record.copy(
            deletePending = true,
            nextAttemptAtMillis = 0,
        )
        AtomicFileWriter.write(item.metadataFile, gson.toJson(updated))
    }

    @Synchronized
    fun remove(eventId: String) {
        recordFile(eventId).takeIf { it.exists() }?.delete()
    }

    private fun readAll(): List<JankQueueItem> {
        return root
            .listFiles { file -> file.isFile && file.extension == RECORD_FILE_EXTENSION }
            .orEmpty()
            .mapNotNull { file ->
                val record = readRecord(file)
                if (record == null) {
                    file.delete()
                    null
                } else {
                    JankQueueItem(record, file)
                }
            }
    }

    private fun readRecord(file: File): JankUploadRecord? {
        return runCatching {
            val record = gson.fromJson(
                file.readText(StandardCharsets.UTF_8),
                JankUploadRecord::class.java,
            )
            requireNotNull(record)
            require(record.eventId.matches(EVENT_ID_PATTERN))
            require(record.fileName == "${record.eventId}$JANK_ARTIFACT_SUFFIX")
            require(file.nameWithoutExtension == record.eventId)
            require(record.createdAtMillis >= 0)
            require(record.attempts >= 0)
            require(record.nextAttemptAtMillis >= 0)
            record
        }.getOrNull()
    }

    private fun recordFile(eventId: String): File {
        require(eventId.matches(EVENT_ID_PATTERN)) { "eventId contains unsupported characters" }
        return File(root, "$eventId.$RECORD_FILE_EXTENSION")
    }

    companion object {
        const val JANK_ARTIFACT_SUFFIX = ".rheajank.zip"
        private const val RECORD_FILE_EXTENSION = "json"
        private const val MAX_ATTEMPTS = 1_000
        private val EVENT_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        fun validArtifactName(fileName: String): Boolean {
            if (!fileName.endsWith(JANK_ARTIFACT_SUFFIX)) {
                return false
            }
            return eventIdFromFileName(fileName).matches(EVENT_ID_PATTERN)
        }

        fun eventIdFromFileName(fileName: String): String {
            return fileName.removeSuffix(JANK_ARTIFACT_SUFFIX)
        }
    }
}
