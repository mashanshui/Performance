package com.example.performance

import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 在真实设备上测量内存三项原始采集操作的耗时，并比较两种 PSS API。
 *
 * 该测试只测采集本身，不包含事件序列化、磁盘队列和网络上传，避免把不同阶段的成本混在一起。
 */
@RunWith(AndroidJUnit4::class)
class MemorySamplingTimingTest {

    @Test
    fun measureMemorySamplingTimingOnDevice() {
        val memoryInfo = Debug.MemoryInfo()
        repeat(WARMUP_COUNT) {
            capturePssBytes(memoryInfo)
            capturePssBytesDirect()
            captureVssBytes()
            captureJavaHeapUsedBytes()
            captureAll(memoryInfo)
            captureAllDirect()
        }

        val pssNanos = LongArray(SAMPLE_COUNT)
        val directPssNanos = LongArray(SAMPLE_COUNT)
        val vssNanos = LongArray(SAMPLE_COUNT)
        val javaHeapNanos = LongArray(SAMPLE_COUNT)
        val combinedNanos = LongArray(SAMPLE_COUNT)
        val directCombinedNanos = LongArray(SAMPLE_COUNT)
        repeat(SAMPLE_COUNT) { index ->
            pssNanos[index] = elapsedNanos { capturePssBytes(memoryInfo) }
            directPssNanos[index] = elapsedNanos { capturePssBytesDirect() }
            vssNanos[index] = elapsedNanos { captureVssBytes() }
            javaHeapNanos[index] = elapsedNanos { captureJavaHeapUsedBytes() }
            combinedNanos[index] = elapsedNanos { captureAll(memoryInfo) }
            directCombinedNanos[index] = elapsedNanos { captureAllDirect() }
        }

        val pssBytes = capturePssBytes(memoryInfo)
        val directPssBytes = capturePssBytesDirect()
        val vssBytes = captureVssBytes()
        val javaHeapUsedBytes = captureJavaHeapUsedBytes()
        Log.i(
            TAG,
            "device=${Build.MODEL}, api=${Build.VERSION.SDK_INT}, " +
                "abis=${Build.SUPPORTED_ABIS.joinToString()}, " +
                "valuesBytes(pss=$pssBytes,directPss=$directPssBytes," +
                "vss=$vssBytes,javaHeap=$javaHeapUsedBytes)",
        )
        Log.i(TAG, "pss=${formatStats(pssNanos)}")
        Log.i(TAG, "directPss=${formatStats(directPssNanos)}")
        Log.i(TAG, "vss=${formatStats(vssNanos)}")
        Log.i(TAG, "javaHeap=${formatStats(javaHeapNanos)}")
        Log.i(TAG, "combined=${formatStats(combinedNanos)}")
        Log.i(TAG, "directCombined=${formatStats(directCombinedNanos)}")

        assertTrue("PSS must be a non-negative byte count", pssBytes >= 0L)
        assertTrue("Debug.getPss() must be a non-negative byte count", directPssBytes >= 0L)
        assertTrue("VSS must be available from /proc/self/status", vssBytes >= 0L)
        assertTrue("Java heap used must be a non-negative byte count", javaHeapUsedBytes >= 0L)
    }

    /** 采集一组值但不创建事件对象，用于隔离三项采集操作的成本。 */
    private fun captureAll(memoryInfo: Debug.MemoryInfo) {
        val pssBytes = capturePssBytes(memoryInfo)
        val vssBytes = captureVssBytes()
        val javaHeapUsedBytes = captureJavaHeapUsedBytes()
        check(pssBytes >= 0L && vssBytes >= 0L && javaHeapUsedBytes >= 0L)
    }

    /** Debug.MemoryInfo 的 totalPss 单位是 KiB，协议要求上传字节。 */
    private fun capturePssBytes(memoryInfo: Debug.MemoryInfo): Long {
        Debug.getMemoryInfo(memoryInfo)
        return memoryInfo.totalPss.toLong() * BYTES_PER_KIB
    }

    /** Debug.getPss() 直接返回本进程 PSS，单位同样是 KiB。 */
    private fun capturePssBytesDirect(): Long {
        return Debug.getPss() * BYTES_PER_KIB
    }

    /** 使用 Debug.getPss() 的组合采集路径，用于与 Debug.MemoryInfo 对比。 */
    private fun captureAllDirect() {
        val pssBytes = capturePssBytesDirect()
        val vssBytes = captureVssBytes()
        val javaHeapUsedBytes = captureJavaHeapUsedBytes()
        check(pssBytes >= 0L && vssBytes >= 0L && javaHeapUsedBytes >= 0L)
    }

    /** VmSize 是进程虚拟地址空间大小，/proc 字段数值单位为 KiB。 */
    private fun captureVssBytes(): Long {
        val vmSizeKib = File("/proc/self/status").bufferedReader().use { reader ->
            reader.lineSequence()
                .firstOrNull { it.startsWith("VmSize:") }
                ?.substringAfter(':')
                ?.trim()
                ?.let { valueWithUnit ->
                    val separator = valueWithUnit.indexOfFirst { it == ' ' || it == '\t' }
                    val numeric = if (separator >= 0) {
                        valueWithUnit.substring(0, separator)
                    } else {
                        valueWithUnit
                    }
                    numeric.toLongOrNull()
                }
        }
        return vmSizeKib?.times(BYTES_PER_KIB) ?: -1L
    }

    /** 服务端约定的 Java 堆已使用口径，不主动触发 GC。 */
    private fun captureJavaHeapUsedBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun elapsedNanos(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtimeNanos()
        block()
        return SystemClock.elapsedRealtimeNanos() - start
    }

    private fun formatStats(valuesNanos: LongArray): String {
        val sorted = valuesNanos.sortedArray()
        fun millis(nanos: Long): Double = nanos / NANOS_PER_MILLI
        fun percentile(ratio: Double): Double {
            val index = ((sorted.size - 1) * ratio).roundToInt()
            return millis(sorted[index])
        }
        return String.format(
            Locale.US,
            "count=%d min=%.3fms mean=%.3fms p50=%.3fms p90=%.3fms p95=%.3fms p99=%.3fms max=%.3fms",
            valuesNanos.size,
            millis(sorted.first()),
            valuesNanos.average() / NANOS_PER_MILLI,
            percentile(0.50),
            percentile(0.90),
            percentile(0.95),
            percentile(0.99),
            millis(sorted.last()),
        )
    }

    private companion object {
        private const val TAG = "MemorySamplingTiming"
        private const val WARMUP_COUNT = 10
        private const val SAMPLE_COUNT = 120
        private const val BYTES_PER_KIB = 1024L
        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}
