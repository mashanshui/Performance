package com.shanshui.performance

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 用于真机验证 Activity 泄漏检测的测试页面。
 *
 * “制造泄漏并销毁”会把当前 Activity 放入进程级静态强引用，模拟业务代码误持有
 * Activity 的场景；检测器完成延迟 GC 和重检后，应在后台线程调用 KOOM dump。
 */
class TestMemoryLeakActivity : AppCompatActivity() {
    private var retainOnDestroy = false
    private lateinit var statusTextView: TextView

    /** 创建测试页面并绑定三种操作；所有按钮回调都运行在主线程。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_test_memory_leak)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        statusTextView = findViewById(R.id.statusTextView)
        findViewById<Button>(R.id.normalDestroyButton).setOnClickListener {
            retainOnDestroy = false
            updateStatus(getString(R.string.memory_leak_test_normal_status))
            finish()
        }
        findViewById<Button>(R.id.leakDestroyButton).setOnClickListener {
            retainOnDestroy = true
            updateStatus(getString(R.string.memory_leak_test_leak_status))
            finish()
        }
        findViewById<Button>(R.id.clearLeakReferenceButton).setOnClickListener {
            MemoryLeakTestHolder.clear()
            updateStatus(getString(R.string.memory_leak_test_cleared_status))
        }
        updateStatus(getString(R.string.memory_leak_test_ready))
    }

    /** 页面销毁时按测试选择建立强引用；正常销毁路径不改变全局状态。 */
    override fun onDestroy() {
        if (retainOnDestroy) {
            MemoryLeakTestHolder.retain(this)
        }
        super.onDestroy()
    }

    /** 更新当前页面状态，只由主线程按钮回调调用。 */
    private fun updateStatus(status: String) {
        statusTextView.text = status
    }
}

/** 仅供测试页面使用的进程级强引用，模拟 Activity 泄漏来源。 */
private object MemoryLeakTestHolder {
    @Volatile
    private var retainedActivity: TestMemoryLeakActivity? = null

    /** 保留已销毁 Activity，故意制造可被检测器发现的泄漏。 */
    fun retain(activity: TestMemoryLeakActivity) {
        retainedActivity = activity
    }

    /** 清理测试引用，使下一轮 GC 可以回收 Activity。 */
    fun clear() {
        retainedActivity = null
    }
}
