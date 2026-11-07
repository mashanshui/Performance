package com.shanshui.performance

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import com.shanshui.performance.jank.JankDiagnostics
import com.shanshui.performance.jank.JankEvent
import com.shanshui.performance.jank.JankExportCallback
import com.shanshui.performance.jank.JankExportRequestStatus
import com.shanshui.performance.jank.JankExportResult
import com.shanshui.performance.jank.JankExportStatus
import com.shanshui.performance.jank.JankSamplingStatus
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID

/** 只负责制造负载和展示导出结果，采集来源沿用 Application 的初始化配置。 */
class TestJankActivity : AppCompatActivity() {
    private enum class Scenario(val title: String, val scene: String) {
        COMPUTE("纯计算卡顿", "demo_compute"),
        SLEEP("阻塞卡顿", "demo_sleep"),
        SYNC("计算并同步抓栈", "demo_sync_compute")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val exportRetry = JankExportRetry(
        now = SystemClock::elapsedRealtime,
        schedule = { delay, task -> handler.postDelayed({ task() }, delay) },
        export = { event, callback ->
            PerformanceSdk.current()?.exportAndEnqueue(event, callback)
                ?: JankExportRequestStatus.NOT_INITIALIZED
        },
    )
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var durationGroup: RadioGroup
    private lateinit var runButtons: List<Button>
    private lateinit var copyButton: Button
    private var resumed = false
    private var busy = false
    private var accepted = false
    private var generation = 0L
    private var artifactPath: String? = null
    private var summary = ""
    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed) return
            refreshStatus()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_jank)
        enableEdgeToEdge()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<Button>(R.id.closeJankPageButton).setOnClickListener { finish() }
        statusView = findViewById(R.id.samplingStatusText)
        resultView = findViewById(R.id.jankStatusText)
        durationGroup = findViewById(R.id.jankDurationGroup)
        runButtons = listOf(findViewById(R.id.triggerJankButton), findViewById(R.id.triggerSleepJankButton),
                findViewById(R.id.triggerSyncJankButton))
        copyButton = findViewById(R.id.copyJankPathButton)
        runButtons.zip(Scenario.values()).forEach { (button, scenario) ->
            button.setOnClickListener { prepare(scenario) }
        }
        copyButton.setOnClickListener {
            artifactPath?.let { path ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("卡顿采样 ZIP 路径", path))
                Toast.makeText(this, "路径已复制", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshStatus()
        handler.postDelayed(refresh, 500)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacksAndMessages(null)
        exportRetry.cancel()
        if (busy && !accepted) {
            ++generation
            busy = false
            resultView.text = "$summary\n已离开页面，待执行任务或导出重试已取消"
        }
        super.onPause()
    }

    private fun refreshStatus() {
        val sync = samplingStatus(false)
        val async = samplingStatus(true)
        val config = App.JANK_TEST_CONFIG
        val sdk = PerformanceSdk.current()
        val status = "配置：同步${if (config.enableSyncSampling) "开启" else "关闭"}，" +
                "异步${if (config.enableAsyncSampling) "开启" else "关闭"}" +
                "\n同步：${statusText(sync)}　异步：${statusText(async)}" +
                "\n异步配置间隔：${config.asyncSampleIntervalMillis} 毫秒" +
                "\nSDK 导出与队列：${if (sdk?.isJankAvailable == true) "可用" else "不可用"}" +
                if (!hasAvailableSource()) "\n当前没有可用采集来源，可运行卡顿作为对照" else ""
        // 状态未变化时不重设文本，避免持续触发布局和无障碍播报。
        if (statusView.text.toString() != status) statusView.text = status
        val initializing = sync == JankSamplingStatus.INITIALIZING ||
                async == JankSamplingStatus.INITIALIZING
        runButtons.forEach { it.isEnabled = !busy && !initializing }
        for (index in 0 until durationGroup.childCount) {
            durationGroup.getChildAt(index).isEnabled = !busy
        }
    }

    /** 初始化异常时引擎可能遗留初始化中状态，不让页面无限等待。 */
    private fun samplingStatus(async: Boolean): JankSamplingStatus {
        val sdk = PerformanceSdk.current()
        val configured = if (async) App.JANK_TEST_CONFIG.enableAsyncSampling
            else App.JANK_TEST_CONFIG.enableSyncSampling
        if (!App.JANK_TEST_CONFIG.enabled || !configured) return JankSamplingStatus.DISABLED
        if (sdk == null || sdk.jankInitResult.status == com.shanshui.performance.jank.JankInitStatus.FAILED) {
            return JankSamplingStatus.FAILED
        }
        return if (async) JankDiagnostics.getAsyncSamplingStatus() else JankDiagnostics.getSyncSamplingStatus()
    }

    private fun hasAvailableSource(): Boolean = PerformanceSdk.current()?.isJankAvailable == true &&
            (samplingStatus(false) == JankSamplingStatus.RUNNING ||
                    samplingStatus(true) == JankSamplingStatus.RUNNING)

    private fun prepare(scenario: Scenario) {
        if (busy || !resumed ||
            samplingStatus(false) == JankSamplingStatus.INITIALIZING ||
            samplingStatus(true) == JankSamplingStatus.INITIALIZING) return
        val durationMs = when (durationGroup.checkedRadioButtonId) {
            R.id.jankDuration300 -> 300L
            R.id.jankDuration3000 -> 3000L
            else -> 1000L
        }
        busy = true
        accepted = false
        artifactPath = null
        copyButton.isEnabled = false
        val run = ++generation
        summary = "场景：${scenario.title}\n设定时长：$durationMs 毫秒"
        resultView.text = "$summary\n准备执行"
        refreshStatus()
        // 让准备状态先绘制；离开页面会取消这条尚未执行的负载。
        handler.postDelayed({ if (resumed && run == generation) execute(scenario, durationMs, run) }, 100)
    }

    private fun execute(scenario: Scenario, durationMs: Long, run: Long) {
        val occurredAt = System.currentTimeMillis()
        val start = SystemClock.elapsedRealtimeNanos()
        var attempts = 0L
        when (scenario) {
            Scenario.COMPUTE -> computeWithoutCapture(durationMs)
            Scenario.SLEEP -> SystemClock.sleep(durationMs)
            Scenario.SYNC -> {
                val deadline = start + durationMs * 1_000_000L
                while (SystemClock.elapsedRealtimeNanos() < deadline) {
                    val remaining = (deadline - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
                    computeWithoutCapture(minOf(10L, maxOf(1L, remaining)))
                    JankDiagnostics.captureStackTrace(false)
                    ++attempts
                }
            }
        }
        val end = SystemClock.elapsedRealtimeNanos()
        summary += "\n实际耗时：${String.format(Locale.ROOT, "%.1f", (end - start) / 1_000_000.0)} 毫秒" +
                "\n手工抓栈调用次数：$attempts"
        if (!hasAvailableSource()) {
            finishRun("当前没有可用采集来源，本次未导出文件")
            return
        }
        val sdk = PerformanceSdk.current() ?: run {
            finishRun("SDK 不可用，本次未导出文件")
            return
        }
        val event = JankEvent(
            eventId = "demo-${UUID.randomUUID()}", occurredAt = occurredAt,
            sessionId = sdk.sessionId, scene = scenario.scene,
            messageStartNs = start, messageEndNs = end,
            thresholdNs = 100_000_000L, attemptedSampleCount = attempts,
        )
        resultView.text = "$summary\n正在导出"
        exportRetry.submit(event, Completion(this, run),
            accepted = { accepted = true },
            rejected = { status ->
                finishRun(if (status == JankExportRequestStatus.BUSY) "导出繁忙，重试已超时"
                    else "导出未受理：${requestText(status)}")
            },
        )
    }

    private fun complete(run: Long, result: JankExportResult) {
        if (isDestroyed || run != generation) return
        // 使用 SDK 入队前保存的摘要；上传线程可能已经清理了原始文件。
        val validFile = result.artifactPath != null && result.recordCount > 0 &&
                result.artifactSizeBytes > 0 &&
                result.status in listOf(JankExportStatus.SUCCESS, JankExportStatus.PARTIAL)
        artifactPath = result.artifactPath.takeIf { validFile }
        copyButton.isEnabled = validFile
        val outcome = when {
            result.status == JankExportStatus.EMPTY -> "无采样数据"
            !validFile -> "失败"
            result.status == JankExportStatus.PARTIAL -> "部分覆盖"
            else -> "成功"
        }
        finishRun("导出状态：$outcome\n记录数：${result.recordCount}" +
                "\n被覆盖记录数：${result.overwrittenRecordCount}" +
                "\n持久上传队列：${if (result.queued) "已入队（不代表上传成功）" else "未入队"}" +
                if (validFile) "\n文件大小：${result.artifactSizeBytes} 字节\n导出时 ZIP 路径：${result.artifactPath}" +
                    "\n上传成功清理后，此路径可能失效"
                else "\n未生成可用文件")
    }

    private fun finishRun(message: String) {
        busy = false
        accepted = false
        resultView.text = "$summary\n$message"
        if (resumed) refreshStatus()
    }

    // 回调只保存弱引用；已接受的导出可以在页面销毁后自然完成。
    private class Completion(activity: TestJankActivity, private val run: Long) : JankExportCallback {
        private val reference = WeakReference(activity)
        override fun onCompleted(result: JankExportResult) {
            // 不使用会被 onPause 清空的任务队列，否则后台完成会让 busy 永久停留。
            Handler(Looper.getMainLooper()).post {
                reference.get()?.let { if (!it.isDestroyed) it.complete(run, result) }
            }
        }
    }

    companion object {
        @Volatile private var calculation = 1L

        /** 不触发手工采样、不休眠；保留明显的方法名供产物验证使用。 */
        @JvmStatic
        fun computeWithoutCapture(durationMs: Long) {
            val deadline = SystemClock.elapsedRealtimeNanos() + durationMs * 1_000_000L
            var value = calculation
            do {
                repeat(4096) { value = (value xor (value shl 13)) + 0x12345L }
            } while (SystemClock.elapsedRealtimeNanos() < deadline)
            calculation = value
        }

        private fun statusText(value: JankSamplingStatus): String = when (value) {
            JankSamplingStatus.DISABLED -> "关闭"
            JankSamplingStatus.INITIALIZING -> "初始化中"
            JankSamplingStatus.RUNNING -> "采集中"
            JankSamplingStatus.PAUSED -> "已暂停"
            JankSamplingStatus.UNSUPPORTED -> "不支持"
            JankSamplingStatus.FAILED -> "失败"
            JankSamplingStatus.STOPPED -> "已停止"
        }

        private fun requestText(value: JankExportRequestStatus): String = when (value) {
            JankExportRequestStatus.NOT_INITIALIZED -> "采集未初始化"
            JankExportRequestStatus.DISABLED -> "采集已关闭"
            JankExportRequestStatus.INVALID_RANGE -> "时间范围无效"
            JankExportRequestStatus.FUTURE_RANGE -> "时间范围超前"
            JankExportRequestStatus.EMPTY_RANGE -> "本次窗口无采样数据"
            JankExportRequestStatus.STORAGE_UNAVAILABLE -> "存储不可用"
            JankExportRequestStatus.INVALID_EVENT -> "卡顿事件元数据无效"
            JankExportRequestStatus.BUSY -> "导出繁忙"
            JankExportRequestStatus.ACCEPTED -> "已受理"
            JankExportRequestStatus.OTHER -> "未知错误"
        }
    }
}
