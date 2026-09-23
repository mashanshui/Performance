package com.shanshui.performance

import android.app.Application
import com.shanshui.performance.core.PerformanceEventContext
import com.shanshui.performance.identity.RuntimeIdentity
import com.shanshui.performance.jank.JankHandle
import com.shanshui.performance.memory.leak.LeakHandle
import com.shanshui.performance.metrics.MetricsHandle
import com.shanshui.performance.network.NetworkConfig
import com.shanshui.performance.network.TransportSession
import com.shanshui.performance.crash.CrashHandle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证 SDK 聚合层的默认装配、功能开关、身份透传和生命周期责任。 */
class PerformanceSdkIntegrationTest {
    /** 每个测试后关闭进程级 SDK，避免全局注册表污染后续用例。 */
    @After
    fun closeSdkAfterTest() {
        PerformanceSdk.current()?.close()
    }

    /** 验证相同初始化复用实例、开关配置和共享身份均由聚合层透传。 */
    @Test
    fun reusesSameInitializationAndPassesFeatureConfiguration() {
        /** 记录组件工厂调用和共享会话。 */
        val dependencies = RecordingDependencies()
        /** 使用本地地址避免测试初始化触发真实网络请求。 */
        val config = testConfig()
        /** 创建不调用 Android Framework 方法的占位 Application。 */
        val application = Application()

        /** 第一次初始化得到的 SDK 实例。 */
        val first = PerformanceSdk.initializeForTesting(
            application = application,
            appKey = " test-key ",
            config = config,
            dependencies = dependencies,
        )
        /** 相同请求返回的已注册实例。 */
        val reused = PerformanceSdk.initializeForTesting(
            application = application,
            appKey = "test-key",
            config = config,
            dependencies = dependencies,
        )

        assertSame(first, reused)
        assertEquals(1, dependencies.sessionCount)
        assertEquals(config.crash, dependencies.crashConfig)
        assertEquals(config.jank, dependencies.jankConfig)
        assertEquals(config.fps, dependencies.fpsConfig)
        assertEquals(config.memory, dependencies.memoryConfig)
        assertEquals(config.memoryLeak, dependencies.memoryLeakConfig)
        assertEquals(TEST_SESSION_ID, first.sessionId)
        assertEquals(TEST_PROCESS_ID, first.processId)
        assertEquals("test-build", dependencies.context.buildId)

        first.close()
        assertTrue(dependencies.lastSession!!.okHttpClient.dispatcher.executorService.isShutdown)
    }

    /** 验证不同配置初始化被拒绝，并且原实例仍保持为当前实例。 */
    @Test
    fun rejectsDifferentConfigurationWithoutReplacingCurrentInstance() {
        /** 记录第一次初始化的依赖调用。 */
        val dependencies = RecordingDependencies()
        /** 创建测试占位 Application。 */
        val application = Application()
        /** 创建第一个 SDK 配置。 */
        val firstConfig = testConfig()
        /** 仅改变渠道以构造冲突配置。 */
        val conflictConfig = firstConfig.copy(
            service = firstConfig.service.copy(channel = "other-channel"),
        )

        /** 初始化当前 SDK 实例。 */
        val first = PerformanceSdk.initializeForTesting(
            application,
            "test-key",
            firstConfig,
            dependencies,
        )

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            PerformanceSdk.initializeForTesting(
                application,
                "test-key",
                conflictConfig,
                dependencies,
            )
        }
        assertSame(first, PerformanceSdk.current())
        assertEquals(1, dependencies.sessionCount)
    }

    /** 验证关闭后可以重新初始化且不会复用旧会话。 */
    @Test
    fun closeClearsRegistryAndAllowsFreshInitialization() {
        /** 记录会话创建次数。 */
        val dependencies = RecordingDependencies()
        /** 创建测试占位 Application。 */
        val application = Application()
        /** 创建 SDK 配置。 */
        val config = testConfig()

        /** 初始化第一代 SDK。 */
        val first = PerformanceSdk.initializeForTesting(application, "test-key", config, dependencies)
        first.close()
        /** 关闭后初始化第二代 SDK。 */
        val second = PerformanceSdk.initializeForTesting(application, "test-key", config, dependencies)

        assertNotSame(first, second)
        assertEquals(2, dependencies.sessionCount)
        second.close()
    }

    /** 构造覆盖功能开关和共享 buildId 的测试配置。 */
    private fun testConfig(): PerformanceConfig {
        return PerformanceConfig(
            service = ServiceConfig(
                baseUrl = "http://127.0.0.1:18080",
                environment = "test",
                channel = "sdk-integration",
                buildId = "test-build",
            ),
            crash = CrashConfig(enabled = true),
            jank = JankConfig(enabled = false),
            fps = FpsConfig(enabled = true),
            memory = MemoryConfig(enabled = false),
            memoryLeak = MemoryLeakConfig(enabled = false),
        )
    }

    /** 使用固定身份和元数据构造不依赖 Android Framework 的上下文。 */
    private class RecordingDependencies : PerformanceSdkDependencies {
        /** 已创建的传输会话。 */
        private val sessions = mutableListOf<TransportSession>()

        /** 最近一次创建的传输会话。 */
        var lastSession: TransportSession? = null
            private set

        /** 最近一次创建的共享上下文。 */
        lateinit var context: PerformanceEventContext
            private set

        /** 最近一次收到的 Crash 配置。 */
        var crashConfig: CrashConfig? = null
            private set

        /** 最近一次收到的 Jank 配置。 */
        var jankConfig: JankConfig? = null
            private set

        /** 最近一次收到的 FPS 配置。 */
        var fpsConfig: FpsConfig? = null
            private set

        /** 最近一次收到的内存配置。 */
        var memoryConfig: MemoryConfig? = null
            private set

        /** 最近一次收到的 Activity 泄漏配置。 */
        var memoryLeakConfig: MemoryLeakConfig? = null
            private set

        /** 返回创建共享会话的次数。 */
        val sessionCount: Int
            get() = sessions.size

        /** 创建真实 TransportSession，以验证聚合层最终关闭共享资源。 */
        override fun createSession(config: NetworkConfig): TransportSession {
            /** 本次测试创建的共享传输会话。 */
            val session = TransportSession.create(config)
            sessions += session
            lastSession = session
            return session
        }

        /** 返回固定上下文，并保留聚合层传入的环境、渠道和 buildId。 */
        override fun createContext(
            application: Application,
            metadataConfig: com.shanshui.performance.core.SharedMetadataConfig,
        ): PerformanceEventContext {
            context = PerformanceEventContext(
                runtimeIdentity = RuntimeIdentity(TEST_SESSION_ID, TEST_PROCESS_ID),
                anonymousDeviceId = "anonymous-device",
                applicationMetadata = ApplicationMetadata("com.example.test", "1.0", 1),
                buildId = metadataConfig.buildId ?: "fallback-build",
                environment = metadataConfig.environment,
                channel = metadataConfig.channel,
            )
            return context
        }

        /** 记录 Crash 配置；返回 null 模拟关闭后的能力句柄。 */
        override fun createCrash(
            application: Application,
            config: CrashConfig,
            context: PerformanceEventContext,
            session: TransportSession,
            schemaVersion: Int,
        ): CrashHandle? {
            crashConfig = config
            return null
        }

        /** 记录 Jank 配置；返回 null 模拟不支持当前进程。 */
        override fun createJank(
            application: Application,
            config: JankConfig,
            context: PerformanceEventContext,
            session: TransportSession,
        ): JankHandle? {
            jankConfig = config
            return null
        }

        /** 记录 FPS 和内存配置；返回 null 模拟进程降级。 */
        override fun createMetrics(
            application: Application,
            fpsConfig: FpsConfig,
            memoryConfig: MemoryConfig,
            context: PerformanceEventContext,
            session: TransportSession,
        ): MetricsHandle? {
            this.fpsConfig = fpsConfig
            this.memoryConfig = memoryConfig
            return null
        }

        /** 记录 Activity 泄漏配置；返回 null 模拟关闭能力但保留准备阶段调用。 */
        override fun createLeak(
            application: Application,
            config: MemoryLeakConfig,
            context: PerformanceEventContext,
            session: TransportSession,
        ): LeakHandle? {
            memoryLeakConfig = config
            return null
        }
    }

    /** 测试主进程的 sessionId。 */
    private companion object {
        /** 固定的 sessionId。 */
        const val TEST_SESSION_ID = "11111111-1111-4111-8111-111111111111"

        /** 固定的子进程 processId。 */
        const val TEST_PROCESS_ID = "22222222-2222-4222-8222-222222222222"
    }
}
