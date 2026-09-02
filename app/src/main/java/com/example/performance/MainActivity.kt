package com.example.performance

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bytedance.rheatrace.RheaTrace3
import com.example.nativelib.LooperMonitor
import com.example.nativelib.NativeLib
import com.example.nativelib.PerformanceSdk
import com.example.nativelib.fps.FpsHelper
import com.example.nativelib.thread.ThreadUtils
import com.example.performance.demo.GcInhibitDemo
import kotlin.jvm.java

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val nativeLib by lazy { NativeLib() }
    private var messageStartNs: Long = 0
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
//        thread {
//            Log.e(TAG, "onCreate: " + Thread.currentThread().threadId())
//            BindCoreUtils.bindCurrentThreadToMaxCore()
//            var i = 0
//            repeat(10000000){
//                i++
//            }
//        }
//        GcInhibitDemo().test()
        startActivity(Intent(this, TestFPSActivity::class.java))
        LooperMonitor.sMainMonitor.register(object : LooperMonitor.LooperListener {
            override fun onMessageBegin(log: String) {
                messageStartNs = SystemClock.elapsedRealtimeNanos()
            }

            override fun onMessageEnd(log: String) {
                val endNs = SystemClock.elapsedRealtimeNanos()
                if (endNs > messageStartNs + 100000000) {
                    Log.e(TAG, "onMessageEnd: ")
                    RheaTrace3.captureStackTrace(false)
                    val event = RheaTrace3.JankEvent.builder()
                        .setEventId("demo-jank-$endNs")
                        .setOccurredAt(System.currentTimeMillis())
                        .setSessionId("demo-session")
                        .setScene("main_activity")
                        .setMessageStartNs(messageStartNs)
                        .setMessageEndNs(endNs)
                        .setThresholdNs(100_000_000L)
                        .setAttemptedSampleCount(1)
                        .build()
                    val exportRequest = PerformanceSdk.current()?.exportAndEnqueue(event) { uploadResult ->
                        if (uploadResult.exportResult.isSuccess) {
                            Log.i(
                                TAG,
                                "jank export completed: eventId=${event.eventId} " +
                                    "status=${uploadResult.exportResult.status.name} " +
                                    "queued=${uploadResult.queued}",
                            )
                        } else {
                            Log.w(
                                TAG,
                                "jank export failed: eventId=${event.eventId} " +
                                    "status=${uploadResult.exportResult.status.name}",
                            )
                        }
                    } ?: RheaTrace3.ExportRequestResult.NOT_INITIALIZED
                    Log.i(
                        TAG,
                        "jank export request submitted: eventId=${event.eventId} " +
                            "result=${exportRequest.name}",
                    )
                }
            }
        })
    }
}
