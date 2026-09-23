package com.example.modularconsumer.full

import android.app.Application
import com.shanshui.performance.NativeLib
import com.shanshui.performance.JankConfig
import com.shanshui.performance.MemoryLeakConfig
import com.shanshui.performance.PerformanceConfig
import com.shanshui.performance.PerformanceSdk
import java.io.File
import org.json.JSONObject

/** 全量 SDK 加 Native Tools 的独立消费者。 */
class FullConsumerApplication : Application() {
    /** 保存全量 SDK 句柄，验证宿主拥有关闭责任。 */
    private var sdk: PerformanceSdk? = null

    /** 关闭 Rhea/KOOM 的精简设备烟测配置；两项独立能力在 6.3 单独验收。 */
    private val smokeConfig = PerformanceConfig(
        jank = JankConfig(enabled = false),
        memoryLeak = MemoryLeakConfig(enabled = false),
    )

    /** 使用公开聚合入口初始化 SDK，并解析 Native Tools 的公开 JNI 类。 */
    override fun onCreate() {
        super.onCreate()
        initializeSdk()
    }

    /** 运行 Release 设备烟测，覆盖重复初始化、关闭重启、身份序列化和 JNI 调用。 */
    fun runSmokeCycle(): String {
        val first = requireNotNull(sdk) { "Full consumer was not initialized" }
        val repeated = PerformanceSdk.initialize(this, "consumer-key", smokeConfig)
        check(first === repeated) { "Repeated initialization did not reuse SDK" }
        val nativeResult = NativeLib().stringFromJNI()
        check(nativeResult == "Hello from C++") { "Native JNI call returned $nativeResult" }
        val serialized = JSONObject()
            .put("sessionId", first.sessionId)
            .put("processId", first.processId)
            .put("native", nativeResult)
            .toString()
        val restored = JSONObject(serialized)
        check(restored.getString("sessionId") == first.sessionId)
        first.close()
        check(PerformanceSdk.current() == null) { "SDK close did not clear registry" }
        initializeSdk()
        val restarted = requireNotNull(sdk)
        check(restarted.sessionId == first.sessionId)
        check(restarted.processId == first.processId)
        restarted.close()
        sdk = null
        File(noBackupFilesDir, "modular-consumer-smoke.json").writeText(serialized)
        return serialized
    }

    /** 通过公开 SDK 聚合入口创建全量实例并解析 Native Tools。 */
    private fun initializeSdk() {
        sdk = PerformanceSdk.initialize(this, "consumer-key", smokeConfig)
        NativeLib()
    }
}
