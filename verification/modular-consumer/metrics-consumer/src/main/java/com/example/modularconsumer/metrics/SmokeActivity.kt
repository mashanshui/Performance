package com.example.modularconsumer.metrics

import android.app.Activity
import android.os.Bundle
import android.util.Log

/** 启动后执行 Metrics 精简消费者烟测并立即退出。 */
class SmokeActivity : Activity() {
    /** 执行消费者初始化、关闭、重启恢复和 JSON 身份序列化验证。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { (application as MetricsConsumerApplication).runSmokeCycle() }
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
        const val TAG = "MetricsConsumerSmoke"
    }
}
