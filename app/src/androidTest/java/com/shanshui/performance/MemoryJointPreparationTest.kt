package com.shanshui.performance

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 联合环境中的第一轮真实采集，宿主独立核对请求与 ClickHouse。 */
@RunWith(AndroidJUnit4::class)
class MemoryJointPreparationTest {
    /** 测试实例持有负载，避免局部引用提前死亡影响采集窗口。 */
    @Volatile private var allocation: ByteArray? = null
    /** 制造上涨/下降、前后台、重建及网络恢复，当前轮始终不能进入历史队列。 */
    @Test fun keepsFirstRunLocalDuringLifecycleAndNetworkRecovery() {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.PERFORMANCE_MEMORY_JOINT_TEST)
        /** 真正运行的默认 10/30 秒 Reporter，由 Application 初始化。 */
        val sdk = checkNotNull(PerformanceSdk.current())
        /** 只检查内存目录，不触及其他组件。 */
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "performance-memory-reporter")
        /** 第一份完整快照，用于检查原身份和峰值不回退。 */
        val before = snapshot(root)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            /** 合成内存上涨，持有时间超过默认前台间隔。 */
            allocation = ByteArray(24 * 1024 * 1024).also { bytes -> for (index in bytes.indices step 4096) bytes[index] = 1 }
            SystemClock.sleep(12_000)
            assertNotNull(allocation)
            allocation = null
            System.gc()
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(1_200)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.recreate()
            sdk.flushAsync()
            /** 保存原网络开关，测试结束恢复，不通过循环回环地址假称物理断网。 */
            val wifi = shell("settings get global wifi_on").trim() == "1"
            /** 数据网络与 Wi-Fi 一起变化，触发默认网络回调。 */
            val data = shell("settings get global mobile_data").trim() == "1"
            try {
                shell("svc wifi disable")
                shell("svc data disable")
                SystemClock.sleep(1_000)
            } finally {
                shell("svc wifi ${if (wifi) "enable" else "disable"}")
                shell("svc data ${if (data) "enable" else "disable"}")
            }
            SystemClock.sleep(1_500)
            sdk.flushAsync()
            /** 真实采集及检查点后的当前完整版本。 */
            val after = snapshot(root)
            assertEquals(before.getString("eventId"), after.getString("eventId"))
            assertEquals(before.getString("processId"), after.getString("processId"))
            for (metric in listOf("pss", "vss", "javaHeap")) {
                if (before.getJSONObject("memoryPeakSummary").has(metric)) {
                    assertTrue(after.getJSONObject("memoryPeakSummary").getJSONObject(metric).getLong("bytes") >= before.getJSONObject("memoryPeakSummary").getJSONObject(metric).getLong("bytes"))
                }
            }
            assertTrue(File(root, "events").isDirectory)
            assertTrue(File(root, "events").listFiles().orEmpty().isEmpty())
            assertFalse(File(root, "events/${after.getString("eventId")}.json").exists())
        }
    }

    /** 等待后台首次有效落盘，避免读取原子替换的短暂缺文件状态。 */
    private fun snapshot(root: File): JSONObject {
        /** 明确的有限等待。 */
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            runCatching { JSONObject(File(root, "current.json").readText()) }.getOrNull()?.let { return it }
            SystemClock.sleep(20)
        }
        error("joint current snapshot unavailable")
    }

    /** 仅执行测试内固定的网络开关命令，不输出设备配置。 */
    private fun shell(command: String): String {
        /** UiAutomation 以 shell 身份运行宿主已授权的测试命令。 */
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
