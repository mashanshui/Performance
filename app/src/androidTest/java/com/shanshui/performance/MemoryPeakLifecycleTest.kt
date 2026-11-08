package com.shanshui.performance

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 使用真实 SDK 和 Activity 验证本轮恢复；服务地址固定关闭端口，不访问外部服务。 */
@RunWith(AndroidJUnit4::class)
class MemoryPeakLifecycleTest {
    /** 当前轮即使 flush、旋转和 SDK 重建也不能成为历史任务。 */
    @Test fun sdkReinitializeAndActivityRecreationKeepCurrentRun() {
        /** 获取测试宿主 Application，不清理已有队列或其他组件数据。 */
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        /** 只启用内存指标，使用短间隔减少测试等待。 */
        val config = PerformanceConfig(
            service = ServiceConfig(baseUrl = "http://127.0.0.1:9", buildId = "memory-lifecycle-test"),
            crash = CrashConfig(enabled = false), jank = JankConfig(enabled = false), fps = FpsConfig(enabled = false),
            memoryLeak = MemoryLeakConfig(enabled = false),
            memory = MemoryConfig(foregroundSamplingIntervalMillis = 200, backgroundSamplingIntervalMillis = 200, uploadIntervalMillis = 100),
        )
        /** 内存指标自己的根目录，测试只读取 current 和同 ID 封存文件。 */
        val root = File(application.noBackupFilesDir, "performance-memory-reporter")
        PerformanceSdk.current()?.close()
        /** 第一次受控初始化可能恢复 Application.onCreate 已建立的同轮信封。 */
        val first = PerformanceSdk.initialize(application, "local-memory-lifecycle-key", config)
        try {
            /** 等待后台首个有效快照。 */
            val before = awaitSnapshot(root)
            /** 记录真实共享身份，不能由测试伪造跨轮。 */
            val processId = first.processId
            /** 固定本轮事件 ID。 */
            val eventId = before.getString("eventId")
            repeat(3) { first.flushAsync() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.recreate() }
            first.close()
            /** 同进程重新初始化排在旧采样和最终检查点后恢复。 */
            val second = PerformanceSdk.initialize(application, "local-memory-lifecycle-key", config)
            try {
                assertEquals(processId, second.processId)
                assertEquals(first.sessionId, second.sessionId)
                /** 先固定接管时已存在的截止，不能把旧实例在途的一次保存当作新实例持续采集。 */
                val takeoverTime = awaitSnapshot(root).getJSONObject("memoryPeakSummary").getLong("lastSampledAt")
                /** 等待第一次后续观测。 */
                val resumedOnce = awaitSnapshot(root, takeoverTime)
                /** 读取重新累计后的完整持久版本。 */
                val after = awaitSnapshot(root, resumedOnce.getJSONObject("memoryPeakSummary").getLong("lastSampledAt"))
                assertEquals(eventId, after.getString("eventId"))
                for (field in listOf("sessionId", "processId", "appVersion", "versionCode", "buildId")) assertEquals(before.get(field), after.get(field))
                /** 三项峰值只能保持或增长，观测起点不能变化。 */
                val previous = before.getJSONObject("memoryPeakSummary")
                /** 同轮恢复后的最新区间和独立上下文。 */
                val current = after.getJSONObject("memoryPeakSummary")
                assertEquals(previous.getLong("collectionStartedAt"), current.getLong("collectionStartedAt"))
                assertTrue(current.getLong("lastSampledAt") > previous.getLong("lastSampledAt"))
                for (name in listOf("pss", "vss", "javaHeap")) {
                    if (previous.has(name)) assertTrue(current.getJSONObject(name).getLong("bytes") >= previous.getJSONObject(name).getLong("bytes"))
                }
                second.flushAsync()
                SystemClock.sleep(150)
                assertFalse("current must not enter events", File(root, "events/$eventId.json").exists())
                assertTrue(File(root, "current.json").exists())
            } finally { second.close() }
        } finally { first.close(); PerformanceSdk.current()?.close() }
    }

    /** 主进程判断必须拒绝不匹配的包名，不尝试读取或创建 Reporter 文件。 */
    @Test fun processPredicateRejectsForeignPackage() {
        /** 包名与真实进程不符的占位 Application。 */
        val foreign = object : Application() {
            /** 用不同包名验证真实进程名判断，不接管任何目录。 */
            override fun getPackageName(): String = "not.the.main.process"
        }
        /** 反射访问内部判定，避免为测试扩大公开 API。 */
        val reporterType = Class.forName("com.shanshui.performance.memory.MemoryReporter")
        /** Kotlin companion 的实际实例。 */
        val companion = reporterType.getField("Companion").get(null)
        assertEquals(false, companion.javaClass.getMethod("isProcessAvailable", Application::class.java).invoke(companion, foreign))
    }

    /** 有界等待原子替换后的完整 JSON，不把备份切换瞬间的缺文件当作有效状态。 */
    private fun awaitSnapshot(root: File, afterTime: Long? = null): JSONObject {
        /** 采样必须在三秒内完成，超时保留失败证据。 */
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            runCatching { JSONObject(File(root, "current.json").readText()) }.getOrNull()?.let {
                if (afterTime == null || it.getJSONObject("memoryPeakSummary").getLong("lastSampledAt") > afterTime) return it
            }
            SystemClock.sleep(20)
        }
        error("memory current snapshot unavailable")
    }
}
