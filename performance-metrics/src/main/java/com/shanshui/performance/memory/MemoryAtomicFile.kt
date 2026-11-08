package com.shanshui.performance.memory

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 内存文件专用可靠替换；备份删除前，旧版本始终可恢复。 */
internal class MemoryAtomicFile(
    /** 故障注入仅在测试使用，生产默认为空操作。 */
    private val boundary: (String) -> Unit = {},
) {
    /** 读取最后提交版本；中断替换保留的备份优先恢复。 */
    fun read(file: File): String? {
        /** 替换事务尚未提交时的上一个完整版本。 */
        val backup = File(file.path + ".bak")
        if (backup.exists()) {
            // 不先删除 backup；覆盖失败时旧完整版本仍然留在原路径。
            if (!backup.renameTo(file)) throw IOException("memory backup restore failed")
        }
        // 锁内恢复时遗留临时文件从未提交，不能上传或累积占用配额。
        File(file.path + ".new").takeIf { it.exists() }?.let {
            if (!it.delete()) throw IOException("memory stale temporary cleanup failed")
        }
        return file.takeIf { it.exists() }?.readText(Charsets.UTF_8)
    }

    /** 写入、同步、备份和替换均失败可恢复；禁止删除旧文件后重试重命名。 */
    fun write(file: File, text: String) {
        read(file)
        /** 尚未提交的新版本，不允许进入上传选择。 */
        val temporary = File(file.path + ".new")
        /** 旧版本必须留到新文件完整提交后。 */
        val backup = File(file.path + ".bak")
        try {
            boundary("write")
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
                boundary("sync")
                output.fd.sync()
            }
            if (file.exists() && !file.renameTo(backup)) throw IOException("memory backup failed")
            boundary("replace")
            if (!temporary.renameTo(file)) throw IOException("memory replace failed")
            boundary("commit")
            if (backup.exists() && !backup.delete()) throw IOException("memory backup commit failed")
        } finally {
            temporary.delete()
        }
    }

    /** 仅在调用方已经确保另有可靠副本或明确隔离后移除当前文件。 */
    fun remove(file: File) {
        read(file)
        boundary("remove")
        if (file.exists() && !file.delete()) throw IOException("memory file removal failed")
    }
}
