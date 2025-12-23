package com.example.performance

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.nativelib.NativeLib
import com.example.performance.demo.GcInhibitDemo

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val nativeLib by lazy { NativeLib() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
//        thread {
//            Log.e(TAG, "onCreate: " + Thread.currentThread().threadId())
//            BindCoreUtils.bindCurrentThreadToMaxCore()
//            var i = 0
//            repeat(10000000){
//                i++
//            }
//        }
        GcInhibitDemo().test()
    }
}