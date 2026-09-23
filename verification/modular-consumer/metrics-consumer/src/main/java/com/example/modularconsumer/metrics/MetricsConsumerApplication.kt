package com.example.modularconsumer.metrics

import android.app.Application
import com.shanshui.performance.core.PerformanceEventContextFactory
import com.shanshui.performance.core.SharedMetadataConfig
import com.shanshui.performance.metrics.FpsConfig
import com.shanshui.performance.metrics.MemoryConfig
import com.shanshui.performance.metrics.MetricsComponent
import com.shanshui.performance.network.NetworkConfig
import com.shanshui.performance.network.TransportSession
import java.io.File
import org.json.JSONObject

/** 仅 Metrics 的独立消费者，验证不引入 Crash/Jank/Leak/Native Tools。 */
class MetricsConsumerApplication : Application() {
    /** 保存本次独立消费者创建的 Metrics 句柄。 */
    private var metricsHandle: AutoCloseable? = null

    /** 保存本次独立消费者创建的传输会话。 */
    private var session: TransportSession? = null

    /** 保存本次独立消费者创建的共享事件上下文。 */
    private var eventContext: com.shanshui.performance.core.PerformanceEventContext? = null

    /** 使用发布坐标创建 FPS/内存指标组件。 */
    override fun onCreate() {
        super.onCreate()
        initializeComponents()
    }

    /** 运行 Release 设备烟测，覆盖指标组件、身份序列化、队列目录和关闭恢复。 */
    fun runSmokeCycle(): String {
        val firstContext = requireNotNull(eventContext) { "Metrics consumer was not initialized" }
        val firstSessionId = firstContext.runtimeIdentity.sessionId
        val firstProcessId = firstContext.runtimeIdentity.processId
        val fpsRoot = File(noBackupFilesDir, "performance-fps-reporter")
        val memoryRoot = File(noBackupFilesDir, "performance-memory-reporter")
        check(fpsRoot.isDirectory || memoryRoot.isDirectory) {
            "Metrics queue directory was not created"
        }
        val serialized = JSONObject()
            .put("sessionId", firstSessionId)
            .put("processId", firstProcessId)
            .toString()
        val restored = JSONObject(serialized)
        check(restored.getString("processId") == firstProcessId)
        closeComponents()
        initializeComponents()
        val secondContext = requireNotNull(eventContext)
        check(secondContext.runtimeIdentity.sessionId == firstSessionId)
        check(secondContext.runtimeIdentity.processId == firstProcessId)
        closeComponents()
        check(fpsRoot.isDirectory || memoryRoot.isDirectory) {
            "Metrics queue directory was not recoverable"
        }
        return serialized
    }

    /** 创建共享上下文、Transport 和 Metrics 组件。 */
    private fun initializeComponents() {
        val context = PerformanceEventContextFactory.create(this, SharedMetadataConfig())
        val transport = TransportSession.create(NetworkConfig("https://apm.example.com", "consumer-key"))
        eventContext = context
        session = transport
        metricsHandle = MetricsComponent.start(this, FpsConfig(), MemoryConfig(), context, transport)
    }

    /** 按组件所有权逆序关闭 Metrics 和 Transport。 */
    private fun closeComponents() {
        metricsHandle?.close()
        metricsHandle = null
        session?.close()
        session = null
        eventContext = null
    }
}
