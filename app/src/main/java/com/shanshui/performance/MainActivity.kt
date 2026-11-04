package com.shanshui.performance

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private val nativeLib by lazy { NativeLib() }

    /** 创建主页面并绑定性能测试入口；执行线程为主线程。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        findViewById<Button>(R.id.jankTestButton).setOnClickListener {
            startActivity(Intent(this, TestJankActivity::class.java))
        }
        // FPS 页面通过显式入口打开，避免应用启动时直接改变当前验收页面。
        findViewById<Button>(R.id.fpsTestButton).setOnClickListener {
            startActivity(Intent(this, TestFPSActivity::class.java))
        }
        findViewById<Button>(R.id.memoryLeakTestButton).setOnClickListener {
            startActivity(Intent(this, TestMemoryLeakActivity::class.java))
        }
        // 崩溃测试统一从独立页面触发，覆盖真实未捕获异常的采集与上传链路。
        findViewById<Button>(R.id.crashCasesPageButton).setOnClickListener {
            startActivity(Intent(this, TestCrashActivity::class.java))
        }
    }
}
