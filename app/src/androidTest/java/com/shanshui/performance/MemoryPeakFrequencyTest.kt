package com.shanshui.performance

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/** 顺序比较基线及三个候选，每个配置前后台各十分钟；默认不随普通测试启动。 */
@RunWith(AndroidJUnit4::class)
class MemoryPeakFrequencyTest {
    /** 显式参数启用长实验，结果写入测试专属文件并输出脱敏摘要。 */
    @Test fun compareIntervals() {
        /** 当前设备仪器运行环境。 */
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("memoryFrequency") == "true")
        PerformanceSdk.current()?.close()
        /** 目标应用上下文，测试文件仅放专属路径。 */
        val context = instrumentation.targetContext
        /** 逐阶段保存完整测量结果的专属文件。 */
        val resultFile = File(context.filesDir, "memory-peak-frequency.jsonl")
        /** 中断后只跳过已经完成整段 600 秒的阶段，由宿主先核对原始结果和目标 APK。 */
        val skip = InstrumentationRegistry.getArguments().getString("memoryFrequencySkip")?.toInt() ?: 0
        require(skip in 0..7)
        if (skip == 0) resultFile.writeText("") else check(resultFile.readLines().size == skip)
        /** 目标 APK 的真实构建标识，避免只更换仪器包时误用测试编译期常量。 */
        val targetBuildId = context.classLoader.loadClass("com.shanshui.performance.BuildConfig").getField("PERFORMANCE_BUILD_ID").get(null) as String
        /** 固定配置顺序中的阶段序号。 */
        var phase = 0
        for ((foregroundInterval, backgroundInterval) in listOf(60_000L to 300_000L, 5_000L to 30_000L, 10_000L to 60_000L, 30_000L to 120_000L)) {
            for (foreground in listOf(true, false)) {
                if (phase++ < skip) continue
                /** 本阶段进入前台或返回桌面的操作。 */
                val intent = if (foreground) Intent(context, MainActivity::class.java) else Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                instrumentation.waitForIdleSync()
                /** 当前前后台状态对应的采样等待间隔。 */
                val interval = if (foreground) foregroundInterval else backgroundInterval
                /** 本例使用的内存指标独立目录。 */
                val root = File(context.cacheDir, "memory-frequency-$foregroundInterval-$foreground").apply { deleteRecursively(); mkdirs() }
                /** 直接调用生产采样和可靠快照路径的测试桥。 */
                val probe = MemoryPeakDeviceProbe(root)
                /** 主线程心跳迟到样本，非真实绘制帧时间。 */
                val mainDelays = Collections.synchronizedList(mutableListOf<Long>())
                /** 主线程心跳监测是否继续运行。 */
                val active = AtomicBoolean(true)
                /** 将监测心跳排入真实主线程的 Handler。 */
                val handler = Handler(Looper.getMainLooper())
                /** 每 16 毫秒检查一次迟到的主线程任务。 */
                val monitor = object : Runnable {
                    /** 下一次预期执行的单调时刻。 */ var expected = SystemClock.uptimeMillis()
                    /** 测量 16 ms 主线程心跳迟到，不把它等同真实绘制帧耗时。 */
                    override fun run() {
                        if (!active.get()) return
                        mainDelays.add((SystemClock.uptimeMillis() - expected).coerceAtLeast(0))
                        expected = SystemClock.uptimeMillis() + 16
                        handler.postAtTime(this, expected)
                    }
                }
                handler.post(monitor)
                /** 当前阶段开始时累计进程 CPU 毫秒。 */
                val cpuStart = Process.getElapsedCpuTime()
                /** 当前阶段单调计时起点。 */
                val started = SystemClock.elapsedRealtime()
                /** 完整采样与持久化路径的纳秒耗时样本。 */
                val durations = mutableListOf<Long>()
                /** 下一次允许采样的单调时刻，不追赶延误。 */
                var nextSample = started
                /** 下一次脏快照检查点的单调时刻。 */
                var nextCheckpoint = started + 30_000
                /** 实际落在合成上涨窗口内的采样次数。 */
                var sampledDuringSpike = 0
                /** 本阶段已经启动的合成上涨窗口数。 */
                var spikeCount = 0
                /** 已分配负载的窗口编号，防止重复分配。 */
                var previousSpike = -1L
                /** 仅在合成上涨窗口内保留的 24 MiB 对象。 */
                var allocation: ByteArray? = null
                while (SystemClock.elapsedRealtime() - started < 600_000) {
                    /** 本次调度或测试使用的可控时刻。 */
                    val now = SystemClock.elapsedRealtime()
                    /** 当前阶段已经经过的单调毫秒数。 */
                    val elapsed = now - started
                    // 相同负载：每 70 秒在第 10..20 秒保留并逐页触摸 24 MiB。
                    val spike = elapsed % 70_000 in 10_000..19_999
                    if (spike && previousSpike != elapsed / 70_000) {
                        previousSpike = elapsed / 70_000
                        spikeCount++
                        allocation = ByteArray(24 * 1024 * 1024).also { bytes -> for (i in bytes.indices step 4096) bytes[i] = 1 }
                    } else if (!spike) allocation = null
                    if (now >= nextSample) {
                        /** 本次操作之前的计时或完整快照。 */
                        val before = SystemClock.elapsedRealtimeNanos()
                        probe.sample(foreground, now >= nextCheckpoint)
                        durations += SystemClock.elapsedRealtimeNanos() - before
                        if (spike && allocation != null) sampledDuringSpike++
                        nextSample = SystemClock.elapsedRealtime() + interval
                    }
                    if (now >= nextCheckpoint) { probe.checkpoint(); nextCheckpoint = SystemClock.elapsedRealtime() + 30_000 }
                    SystemClock.sleep(20)
                }
                probe.checkpoint()
                active.set(false)
                handler.removeCallbacks(monitor)
                /** 本阶段实际测满的单调毫秒数。 */
                val duration = SystemClock.elapsedRealtime() - started
                /** 只含合成负载和构建条件的阶段统计。 */
                val result = JSONObject().put("foregroundIntervalMs", foregroundInterval).put("backgroundIntervalMs", backgroundInterval)
                    .put("foreground", foreground).put("durationMs", duration).put("samples", durations.size)
                    .put("pathP50Ms", percentile(durations, .5) / 1_000_000.0).put("pathP95Ms", percentile(durations, .95) / 1_000_000.0)
                    .put("pathP99Ms", percentile(durations, .99) / 1_000_000.0).put("cpuMs", Process.getElapsedCpuTime() - cpuStart)
                    .put("mainHeartbeatP95Ms", percentile(mainDelays, .95)).put("mainHeartbeatP99Ms", percentile(mainDelays, .99))
                    .put("writes", probe.writes).put("jsonBytesWritten", probe.bytesWritten)
                    .put("spikeWindows", spikeCount).put("samplesDuringSpike", sampledDuringSpike)
                    .put("model", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT)
                    .put("abi", android.os.Build.SUPPORTED_ABIS.joinToString()).put("buildId", targetBuildId)
                resultFile.appendText(result.toString() + "\n")
                Log.i("MemoryPeakFrequency", result.toString())
                root.deleteRecursively()
            }
        }
    }

    /** 最近秩分位数；长间隔只有少量样本，保留样本数而不夸大统计稳定性。 */
    private fun percentile(values: List<Long>, ratio: Double): Long = synchronized(values) { values.sorted() }.let { if (it.isEmpty()) 0 else it[(ceil(it.size * ratio).toInt() - 1).coerceIn(it.indices)] }
}
