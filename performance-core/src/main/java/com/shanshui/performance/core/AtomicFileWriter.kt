package com.shanshui.performance.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 在同一文件系统内以临时文件替换方式写入文本，避免进程重启留下半条记录。 */
object AtomicFileWriter {
    /** 将内容持久化到目标文件并在失败时清理临时文件。 */
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

/** 读取或生成跨功能复用的匿名设备标识。 */
class DeviceIdentityStore(
    /** 设备标识持久化文件。 */
    private val file: File,
) {
    /** 获取已有设备 ID，缺失或损坏时原子生成并保存新值。 */
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
