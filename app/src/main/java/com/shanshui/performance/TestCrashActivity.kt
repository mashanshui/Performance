package com.shanshui.performance

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 独立崩溃测试页；每次触发致命异常后需重新启动应用。 */
class TestCrashActivity : AppCompatActivity() {
    /** 初始化可滚动页面，绑定主线程与后台线程的真实未捕获异常。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_test_crash)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            // 避免控件被系统栏和输入法遮挡。
            val padding = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
        findViewById<Button>(R.id.explicitCrashButton).setOnClickListener {
            CrashTestCases.explicitException()
        }
        findViewById<Button>(R.id.nullCrashButton).setOnClickListener {
            CrashTestCases.nullPointer()
        }
        findViewById<Button>(R.id.boundsCrashButton).setOnClickListener {
            CrashTestCases.arrayBounds()
        }
        findViewById<Button>(R.id.stateCrashButton).setOnClickListener {
            // 输入由人工另行记录，不写入异常、日志或额外事件字段。
            val state = readInput(R.id.runtimeStateInput) ?: return@setOnClickListener
            CrashTestCases.runtimeState(state)
        }
        findViewById<Button>(R.id.asyncCrashButton).setOnClickListener {
            // 在主线程读取控件，只把字符串交给后台线程，避免捕获 Activity。
            val response = readInput(R.id.externalResponseInput) ?: return@setOnClickListener
            Thread({ CrashTestCases.externalResponse(response) }, "crash-test-external").start()
        }
        findViewById<Button>(R.id.closeCrashPageButton).setOnClickListener { finish() }
    }

    /** 拒绝空输入；清除首尾空白后作为当前测试的运行时值。 */
    private fun readInput(viewId: Int): String? {
        // 限制输入长度由布局负责；页面不保存这些人工测试数据。
        val input = findViewById<EditText>(viewId).text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(this, R.string.crash_cases_input_required, Toast.LENGTH_SHORT).show()
            return null
        }
        return input
    }
}
