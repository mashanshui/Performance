package com.example.performance

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.example.nativelib.MessageCollect
import com.example.nativelib.fps.FpsHelperV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TestFPSActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "TestFPSActivity"
    }

    private val mHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_test_fpsactivity)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
//        FpsHelper().start(window.decorView, this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FpsHelperV2().start(window)
        }
        val adapter11 = MyAdapter()
        findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = LinearLayoutManager(this@TestFPSActivity)
            adapter = adapter11
            val list = mutableListOf<String>()
            repeat(100) { i ->
                list.add("Item $i")
            }
            adapter11.submitList(list)
        }
        findViewById<Button>(R.id.button1).setOnClickListener { view ->
            repeat(200) { i ->
                mHandler.post {
                    Log.e(TAG, "onCreate: " + i)
                    SystemClock.sleep(10)
                }
            }
        }
        MessageCollect.start()
        lifecycleScope.launch(Dispatchers.Default) {
            delay(10000)
            repeat(10) { i ->
                mHandler.post {
                    Log.e(TAG, "onCreate: " + i)
                    SystemClock.sleep(10)
                }
            }
            delay(20)
            val historyMessageQueue = MessageCollect.getHistoryMessageQueue()
            historyMessageQueue.forEach {
                Log.e(TAG, "getHistoryMessageQueue: $it")
            }
        }
    }

    class MyAdapter : BaseQuickAdapter<String, QuickViewHolder>() {
        var mStuck = 0
        override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
            return QuickViewHolder(R.layout.test_fps_item, parent)
        }

        override fun onBindViewHolder(holder: QuickViewHolder, position: Int, item: String?) {
            if (mStuck == 10) {
                Log.e(TAG, "onBindViewHolder: ")
                Thread.sleep(30)
                mStuck = 0
            }
            mStuck++
            holder.setText(R.id.textView, item)
        }
    }
}