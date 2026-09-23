package com.shanshui.performance.memory.leak

import android.app.Application
import com.shanshui.performance.ApplicationMetadata
import com.shanshui.performance.core.PerformanceEventContext
import com.shanshui.performance.identity.RuntimeIdentity
import com.shanshui.performance.network.NetworkConfig
import com.shanshui.performance.network.TransportSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 验证 Leak 组件对 KOOM 准备、精简关闭和停止所有权的边界。 */
class LeakComponentBoundaryTest {
    /** 验证检测关闭时仍准备公共配置，但不初始化或停止 KOOM 循环。 */
    @Test
    fun disabledLeakPreparesCommonConfigWithoutStartingKoom() {
        val boundary = FakeLeakKoomBoundary()
        val session = createSession()
        try {
            val handle = LeakComponent.startInternal(
                application = Application(),
                config = MemoryLeakConfig(enabled = false),
                context = createContext(),
                session = session,
                koomBoundary = boundary,
            )

            assertFalse(handle.isAvailable)
            assertEquals(1, boundary.ensureCommonConfigCalls)
            assertEquals(0, boundary.initCalls)
            handle.close()
            assertEquals(0, boundary.stopCalls)
        } finally {
            session.close()
        }
    }

    /** 验证分析进程只执行公共配置准备，不注册主进程监控。 */
    @Test
    fun analysisProcessOnlyPreparesCommonConfig() {
        val boundary = FakeLeakKoomBoundary(isAnalysisProcess = true)
        val session = createSession()
        try {
            val handle = LeakComponent.startInternal(
                application = Application(),
                config = MemoryLeakConfig(enabled = true),
                context = createContext(),
                session = session,
                koomBoundary = boundary,
            )

            assertFalse(handle.isAvailable)
            assertEquals(1, boundary.ensureCommonConfigCalls)
            assertEquals(0, boundary.initCalls)
            handle.close()
        } finally {
            session.close()
        }
    }

    /** 验证句柄只停止自己拥有的循环，并且重复关闭只执行一次。 */
    @Test
    fun handleStopsOwnedLoopOnce() {
        var stopCalls = 0
        val handle = LeakHandle(
            watcher = null,
            monitorStarted = true,
            isAvailable = true,
            stopMonitor = { stopCalls++ },
        )

        handle.close()
        handle.close()

        assertEquals(1, stopCalls)
    }

    /** 创建测试用传输会话。 */
    private fun createSession(): TransportSession {
        return TransportSession.create(NetworkConfig("http://localhost", "test-key"))
    }

    /** 创建不依赖 Android PackageManager 的共享事件上下文。 */
    private fun createContext(): PerformanceEventContext {
        return PerformanceEventContext(
            runtimeIdentity = RuntimeIdentity(
                sessionId = "00000000-0000-4000-8000-000000000001",
                processId = "00000000-0000-4000-8000-000000000001",
            ),
            anonymousDeviceId = "anonymous-device",
            applicationMetadata = ApplicationMetadata("com.example.test", "1.0", 1),
            buildId = "test-build",
        )
    }

    /** 记录 KOOM 边界调用，避免测试触发真实分析进程和 dump。 */
    private class FakeLeakKoomBoundary(
        /** 是否将调用方视为分析子进程。 */
        private val isAnalysisProcess: Boolean = false,
    ) : LeakKoomBoundary {
        /** 公共配置准备次数。 */
        var ensureCommonConfigCalls: Int = 0

        /** 监控循环初始化次数。 */
        var initCalls: Int = 0

        /** 监控循环停止次数。 */
        var stopCalls: Int = 0

        /** 判断测试进程是否为分析子进程。 */
        override fun isHeapAnalysisProcess(application: Application): Boolean = isAnalysisProcess

        /** 记录公共配置准备。 */
        override fun ensureCommonConfig(application: Application) {
            ensureCommonConfigCalls++
        }

        /** 测试设备始终声明支持，以便覆盖后续分支。 */
        override fun isSupported(): Boolean = true

        /** 记录监控循环初始化。 */
        override fun init(
            application: Application,
            reporter: MemoryLeakReportReporter,
        ) {
            initCalls++
        }

        /** 记录监控循环停止。 */
        override fun stop() {
            stopCalls++
        }

        /** 测试不触发真实 dump。 */
        override fun dump() = Unit
    }
}
