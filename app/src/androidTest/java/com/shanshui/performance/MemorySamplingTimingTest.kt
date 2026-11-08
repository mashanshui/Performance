package com.shanshui.performance

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
 * 在 Android 设备上分别测量原始读取与完整运行峰值持久化路径。
 *
 * 原始对照方法比较两种 PSS API；完整路径方法额外覆盖生产累计、可靠 current 写盘和检查点，
 * 两组独立输出，不把原始读取耗时当作峰值上报完整开销；均不包含网络上传。
 */
@RunWith(AndroidJUnit4::class)
class MemorySamplingTimingTest {

    /** 完整生产采样/累计/可靠落盘耗时，同时确定性覆盖新高及检查点路径。 */
    @Test
    fun measureFullPeakPersistencePath() {
        /** 目标测试上下文，不访问其他应用。 */
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        /** 专用临时目录，不能清空实际 Reporter 队列。 */
        val root = File(context.cacheDir, "memory-full-path-test").apply { deleteRecursively(); mkdirs() }
        try {
            /** 直接驱动生产实现的测试桥。 */
            val probe = MemoryPeakDeviceProbe(root)
            /** 包含实际读取、比较、可靠替换及检查点的纳秒耗时。 */
            val fullPath = LongArray(SAMPLE_COUNT) { elapsedNanos { probe.sample(true, checkpoint = true) } }
            /** 新高前成功写盘次数，用于证明立即保存。 */
            val previousWrites = probe.writes
            probe.synthetic(8_000_000_000_000_000L, true)
            assertTrue(probe.writes > previousWrites)
            /** 低值普通采样不应增加此计数，检查点才可增加。 */
            val highWrites = probe.writes
            SystemClock.sleep(2)
            probe.synthetic(1, false)
            assertTrue(probe.writes == highWrites)
            probe.checkpoint()
            assertTrue(probe.writes > highWrites)
            assertTrue(probe.snapshot().contains("memoryPeakSummary"))
            Log.i(TAG, "fullPeakPath=${formatStats(fullPath)} writes=${probe.writes} jsonBytesWritten=${probe.bytesWritten}")
        } finally { root.deleteRecursively() }
    }

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
