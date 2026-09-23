package com.example.modularconsumer.full

import android.app.Activity
import android.os.Bundle
import android.util.Log

/** 启动后执行全量消费者烟测并立即退出。 */
class SmokeActivity : Activity() {
    /** 执行 SDK 重复初始化、关闭重启和 Native Tools JNI 验证。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { (application as FullConsumerApplication).runSmokeCycle() }
            .onSuccess { Log.i(TAG, "consumer smoke passed payload=$it") }
            .onFailure { throwable ->
                Log.e(TAG, "consumer smoke failed", throwable)
                throw throwable
            }
        finish()
    }

    /** 当前消费者烟测日志标签。 */
    private companion object {
        /** 当前消费者烟测日志标签。 */
        const val TAG = "FullConsumerSmoke"
    }
}
