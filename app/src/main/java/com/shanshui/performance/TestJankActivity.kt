package com.shanshui.performance

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shanshui.performance.jank.JankDiagnostics
import com.shanshui.performance.jank.JankEvent
import com.shanshui.performance.jank.JankExportStatus

/** 独立卡顿测试页；仅在页面处于前台时注册演示消息监听。 */
class TestJankActivity : AppCompatActivity() {
    private val TAG = "TestJankActivity"
    private lateinit var triggerButton: Button
    private var timingActive = false
    private val looperMonitor by lazy { LooperMonitor.sMainMonitor }

    /** 创建测试控件；大量计算仍通过 post 放到下一条主线程消息执行。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_test_jank)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        triggerButton = findViewById(R.id.triggerJankButton)
        triggerButton.setOnClickListener {
            // 不累积尚未执行的请求，避免连续点击形成后台测试任务。
            triggerButton.removeCallbacks(jankTestRunnable)
            triggerButton.post(jankTestRunnable)
        }
        findViewById<Button>(R.id.closeJankPageButton).setOnClickListener { finish() }
    }

    /** 页面进入前台时开始消息计时，首页及其他测试页不持有此监听。 */
    override fun onResume() {
        super.onResume()
        looperMonitor.register(messageListener)
    }

    /** 复用原首页的大量计算样本，耗时由设备与构建优化决定。 */
    private val jankTestRunnable = Runnable {
        var value = 0
        Log.i(TAG, "jank test started")
        repeat(100000) {
            value = ThreadTest().sdtdsf(value)
        }
        Log.i(TAG, "jank test completed: result=$value")
        findViewById<TextView>(R.id.jankStatusText).setText(R.string.jank_test_completed)
    }

    private val messageListener = object : LooperMonitor.LooperListener {
        /** 开启消息计时；Printer 恢复丢弃未完成边界时先收尾旧会话。 */
        override fun onMessageBegin(log: String, beginNs: Long) {
            if (timingActive) {
                timingActive = false
                JankDiagnostics.endStackTiming()
            }
            JankDiagnostics.beginStackTiming()
            timingActive = true
//            Log.e(TAG, "onMessageBegin: ")
        }

        /** 在主线程消息超过阈值时导出既有卡顿事件。 */
        override fun onMessageEnd(log: String, beginNs: Long, endNs: Long) {
//            Log.e(TAG, "onMessageEnd: ${(endNs - beginNs)/1000000}")
            if (!timingActive) {
                return
            }
            timingActive = false
            // 每条消息均关闭计时会话，仅长消息输出诊断并导出。
            val report = JankDiagnostics.endStackTiming()
            if (endNs - beginNs > 60_000_000L) {
                Log.e(TAG, "onMessageEnd: $endNs")
                // 执行消息任务
                for (line in report.split("\n".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()) {
                    Log.i("StackDiagnostics", line)
                }
                JankDiagnostics.captureStackTrace(false)
                // Jank 事件必须使用 SDK 当前进程共享的 sessionId，不能再使用演示占位值。
                val sdk = PerformanceSdk.current() ?: return
                val event = JankEvent(
                    eventId = "demo-jank-$endNs",
                    occurredAt = System.currentTimeMillis(),
                    sessionId = sdk.sessionId,
                    scene = "jank_test",
                    messageStartNs = beginNs,
                    messageEndNs = endNs,
                    thresholdNs = 60_000_000L,
                    attemptedSampleCount = 1L,
                )
                val exportRequest = sdk.exportAndEnqueue(event) { uploadResult ->
                    Log.e(TAG, "onMessageEnd: ${uploadResult.artifactPath.orEmpty()}")
                    if (uploadResult.status == JankExportStatus.SUCCESS ||
                        uploadResult.status == JankExportStatus.PARTIAL
                    ) {
                        Log.i(
                            TAG,
                            "jank export completed: eventId=${event.eventId} " +
                                "status=${uploadResult.status.name} " +
                                "queued=${uploadResult.queued}",
                        )
                    } else {
                        Log.w(
                            TAG,
                            "jank export failed: eventId=${event.eventId} " +
                                "status=${uploadResult.status.name}",
                        )
                    }
                }
                Log.i(
                    TAG,
                    "jank export request submitted: eventId=${event.eventId} " +
                        "result=${exportRequest.name}",
                )
            }
        }
    }

    /** 离开页面时取消待执行的计算，并收尾可能由暂停消息开启的计时会话。 */
    override fun onPause() {
        triggerButton.removeCallbacks(jankTestRunnable)
        looperMonitor.unregister(messageListener)
        try {
            if (timingActive) {
                timingActive = false
                JankDiagnostics.endStackTiming()
            }
        } finally {
            super.onPause()
        }
    }
}
