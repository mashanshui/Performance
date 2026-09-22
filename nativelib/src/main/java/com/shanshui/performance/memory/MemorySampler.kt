package com.shanshui.performance.memory

import android.os.Debug
import java.io.File

/** 一次采样得到的三项原始内存值，单位均为字节。 */
internal data class MemorySampleValues(
    val pssBytes: Long?,
    val vssBytes: Long?,
    val javaHeapUsedBytes: Long?,
) {
    fun hasValue(): Boolean = pssBytes != null || vssBytes != null || javaHeapUsedBytes != null
}

/**
 * 将系统内存 API 统一转换为服务端的字节口径。
 * 读取器作为依赖注入，便于 JVM 测试覆盖失败、零值和溢出分支。
 */
internal class MemorySampler(
    private val pssReader: () -> Long = { Debug.getPss() },
    private val vssReader: () -> Long? = { readVssBytes() },
    private val javaHeapReader: () -> Long? = { readJavaHeapUsedBytes() },
) {
    fun sample(): MemorySampleValues {
        return MemorySampleValues(
            pssBytes = runCatching { pssReader() }.getOrNull()?.takeIf { it >= 0L }
                ?.let(::kibToBytes),
            vssBytes = runCatching { vssReader() }.getOrNull()
                ?.takeIf { it in 0L..MAX_MEMORY_BYTES },
            javaHeapUsedBytes = runCatching { javaHeapReader() }.getOrNull()
                ?.takeIf { it in 0L..MAX_MEMORY_BYTES },
        )
    }

    private fun kibToBytes(value: Long): Long? {
        return if (value <= MAX_MEMORY_BYTES / BYTES_PER_KIB) {
            value * BYTES_PER_KIB
        } else {
            null
        }
    }

    companion object {
        const val BYTES_PER_KIB = 1024L
        /** 服务端 JSON 数值安全整数上限（2^53-1）。 */
        const val MAX_MEMORY_BYTES = 9_007_199_254_740_991L
        private val VM_SIZE_PATTERN = Regex("^VmSize:\\s+(\\d+)\\s+kB(?:\\s*)$", RegexOption.IGNORE_CASE)

        /** 读取当前进程虚拟地址空间，/proc 单位为 kB。 */
        internal fun readVssBytes(statusFile: File = File("/proc/self/status")): Long? {
            return runCatching {
                statusFile.useLines { lines ->
                    lines.mapNotNull { line ->
                        VM_SIZE_PATTERN.matchEntire(line.trim())?.groupValues?.getOrNull(1)
                            ?.toLongOrNull()
                    }.firstOrNull()?.let { kib ->
                        if (kib <= Long.MAX_VALUE / BYTES_PER_KIB) {
                            kib * BYTES_PER_KIB
                        } else {
                            null
                        }
                    }
                }
            }.getOrNull()
        }

        /** Java 堆已使用字节，严格按 totalMemory - freeMemory 口径计算。 */
        internal fun readJavaHeapUsedBytes(runtime: Runtime = Runtime.getRuntime()): Long? {
            return runCatching {
                val total = runtime.totalMemory()
                val free = runtime.freeMemory()
                if (total >= free) total - free else null
            }.getOrNull()
        }

        /** 便于日志输出稳定的单位名称，不进入上传载荷。 */
        internal fun formatBytes(value: Long?): String {
            return value?.toString() ?: "null"
        }
    }
}
