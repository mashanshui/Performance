package com.shanshui.performance

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shanshui.performance.jank.JankDiagnostics
import com.shanshui.performance.jank.JankEvent
import com.shanshui.performance.jank.JankExportStatus

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val nativeLib by lazy { NativeLib() }
    private var timingActive = false
    private val looperMonitor by lazy { LooperMonitor.sMainMonitor }

    /** 创建主页面并绑定卡顿测试、FPS 测试两个入口；执行线程为主线程。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        findViewById<Button>(R.id.button).setOnClickListener { view ->
            var i = 0
            view.post {
                Log.e(TAG, "onCreate: 1")
                repeat(100000) {
                    i = ThreadTest().sdtdsf(i)
                }
                Log.e(TAG, "onCreate: 2")
            }
        }
        // FPS 页面通过显式入口打开，避免应用启动时直接改变当前验收页面。
        findViewById<Button>(R.id.fpsTestButton).setOnClickListener {
            startActivity(Intent(this, TestFPSActivity::class.java))
        }
        findViewById<Button>(R.id.memoryLeakTestButton).setOnClickListener {
            startActivity(Intent(this, TestMemoryLeakActivity::class.java))
        }
        // Crash 测试必须保留为未捕获异常，才能进入 SDK 的默认异常处理器。
        findViewById<Button>(R.id.crashTestButton).setOnClickListener {
            triggerCrashUploadTest()
        }
        looperMonitor.register(messageListener)
    }

    /** 主动制造未捕获 JVM 异常，验证 Crash 事件落盘和崩溃前同步上传。 */
    private fun triggerCrashUploadTest() {
        throw IllegalStateException("Crash upload test triggered from MainActivity")
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
                    scene = "main_activity",
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

    /** 销毁消息本身可能已开启计时，注销不补回调，因此在这里主动收尾。 */
    override fun onDestroy() {
        looperMonitor.unregister(messageListener)
        try {
            if (timingActive) {
                timingActive = false
                JankDiagnostics.endStackTiming()
            }
        } finally {
            super.onDestroy()
        }
    }
}
