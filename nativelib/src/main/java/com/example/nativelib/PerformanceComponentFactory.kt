package com.example.nativelib

import android.app.Application
import com.bytedance.rheatrace.RheaTrace3
import com.example.nativelib.fps.FpsReporter
import com.example.nativelib.config.NativeServiceConfig
import com.example.nativelib.config.toNetworkConfig
import com.example.nativelib.crash.CrashReporter
import com.example.nativelib.crash.CrashReporterConfig
import com.example.nativelib.jank.JankArtifactInitResult
import com.example.nativelib.jank.JankArtifactReporter
import com.example.nativelib.memory.leak.ActivityLeakWatcher
import com.example.nativelib.memory.leak.MemoryLeakReportReporter
import com.example.nativelib.memory.leak.MemoryLeakWatcher
import com.example.nativelib.memory.MemoryReporter
import com.example.nativelib.memory.oom.OOMMonitorInitTask
import com.example.nativelib.network.NetworkClientFactory

/**
 * SDK 初始化所依赖的组件边界。
 *
 * 生产环境使用 [DefaultPerformanceComponentFactory]；测试可以替换 Provider，
 * 模拟组件失败或 Rhea 的设备降级结果，而不必启动真实网络和 native 采集。
 */
internal interface PerformanceComponentFactory {
    fun createNetwork(serviceConfig: NativeServiceConfig): NetworkClientFactory

    fun loadAnonymousDeviceId(application: Application): String

    fun startCrash(
        application: Application,
        config: CrashReporterConfig,
        serviceConfig: NativeServiceConfig,
        networkFactory: NetworkClientFactory,
        anonymousDeviceId: String,
    ): CrashReporter

    fun initializeJank(
        application: Application,
        serviceConfig: NativeServiceConfig,
        networkFactory: NetworkClientFactory,
        onlineTraceConfig: RheaTrace3.OnlineTraceConfig,
    ): JankArtifactInitResult

    /** 创建 Activity 生命周期驱动的 FPS reporter。 */
    fun initializeFps(
        application: Application,
        serviceConfig: NativeServiceConfig,
        fpsConfig: FpsConfig,
        metadata: ApplicationMetadata,
        buildId: String,
        anonymousDeviceId: String,
        networkFactory: NetworkClientFactory,
    ): FpsReporter

    /** 创建主进程内存采集和持久上传 Reporter。 */
    fun initializeMemory(
        application: Application,
        serviceConfig: NativeServiceConfig,
        memoryConfig: MemoryConfig,
        metadata: ApplicationMetadata,
        buildId: String,
        anonymousDeviceId: String,
        networkFactory: NetworkClientFactory,
    ): MemoryReporter

    /** 判断当前进程和 Android API 是否满足 KOOM Java leak 的能力边界。 */
    fun isMemoryLeakAvailable(application: Application): Boolean

    /** 初始化 KOOM 以及内存泄漏报告持久上传器。 */
    fun initializeKoom(
        application: Application,
        serviceConfig: NativeServiceConfig,
        metadata: ApplicationMetadata,
        buildId: String,
        anonymousDeviceId: String,
        networkFactory: NetworkClientFactory,
    )

    /** 创建 Activity 弱引用检测器；默认回调只负责调用 KOOM dump。 */
    fun initializeMemoryLeak(
        application: Application,
        memoryLeakConfig: MemoryLeakConfig,
    ): MemoryLeakWatcher

    /** 停止本 SDK 启动的 KOOM 循环，不重置 KOOM 的进程级 dump 状态。 */
    fun stopKoom()

    fun closeJank()

    fun stopRhea()
}

/** 生产实现，集中保留各 Reporter 的实际创建细节。 */
private object DefaultPerformanceComponentFactory : PerformanceComponentFactory {
    override fun createNetwork(serviceConfig: NativeServiceConfig): NetworkClientFactory {
        return NetworkClientFactory.create(serviceConfig.toNetworkConfig())
    }

    override fun loadAnonymousDeviceId(application: Application): String {
        return CrashReporter.loadAnonymousDeviceId(application)
    }

    override fun startCrash(
        application: Application,
        config: CrashReporterConfig,
        serviceConfig: NativeServiceConfig,
        networkFactory: NetworkClientFactory,
        anonymousDeviceId: String,
    ): CrashReporter {
        return CrashReporter.start(
            context = application,
            config = config,
            serviceConfig = serviceConfig,
            networkFactory = networkFactory,
            anonymousDeviceId = anonymousDeviceId,
        )
    }

    override fun initializeJank(
        application: Application,
        serviceConfig: NativeServiceConfig,
        networkFactory: NetworkClientFactory,
        onlineTraceConfig: RheaTrace3.OnlineTraceConfig,
    ): JankArtifactInitResult {
        return JankArtifactReporter.initialize(
            application = application,
            serviceConfig = serviceConfig,
            networkFactory = networkFactory,
            onlineTraceConfig = onlineTraceConfig,
        )
    }

    /** 创建生产 FPS reporter，并复用统一网络工厂。 */
    override fun initializeFps(
        application: Application,
        serviceConfig: NativeServiceConfig,
        fpsConfig: FpsConfig,
        metadata: ApplicationMetadata,
        buildId: String,
        anonymousDeviceId: String,
        networkFactory: NetworkClientFactory,
    ): FpsReporter {
        return FpsReporter.create(
            application = application,
            serviceConfig = serviceConfig,
            fpsConfig = fpsConfig,
            metadata = metadata,
            buildId = buildId,
            anonymousDeviceId = anonymousDeviceId,
            networkFactory = networkFactory,
        )
    }

    override fun initializeMemory(
        application: Application,
        serviceConfig: NativeServiceConfig,
        memoryConfig: MemoryConfig,
        metadata: ApplicationMetadata,
        buildId: String,
        anonymousDeviceId: String,
        networkFactory: NetworkClientFactory,
    ): MemoryReporter {
        return MemoryReporter.create(
            application = application,
            config = serviceConfig,
            memoryConfig = memoryConfig,
            metadata = metadata,
            buildId = buildId,
            anonymousDeviceId = anonymousDeviceId,
            networkFactory = networkFactory,
        )
    }

    override fun isMemoryLeakAvailable(application: Application): Boolean {
        return MemoryReporter.isProcessAvailable(application) && OOMMonitorInitTask.isSupported()
    }

    override fun initializeKoom(
        application: Application,
        serviceConfig: NativeServiceConfig,
        metadata: ApplicationMetadata,
        buildId: String,
        anonymousDeviceId: String,
        networkFactory: NetworkClientFactory,
    ) {
        val reportReporter = MemoryLeakReportReporter.create(
            application = application,
            serviceConfig = serviceConfig,
            metadata = metadata,
            buildId = buildId,
            anonymousDeviceId = anonymousDeviceId,
            networkFactory = networkFactory,
        )
        OOMMonitorInitTask.init(application, reportReporter)
    }

    override fun initializeMemoryLeak(
        application: Application,
        memoryLeakConfig: MemoryLeakConfig,
    ): MemoryLeakWatcher {
        return ActivityLeakWatcher(
            application = application,
            config = memoryLeakConfig,
            onLeakDetected = { OOMMonitorInitTask.dump() },
        )
    }

    override fun stopKoom() {
        OOMMonitorInitTask.stop()
    }

    override fun closeJank() {
        JankArtifactReporter.close()
    }

    override fun stopRhea() {
        RheaTrace3.stopOnlineTracing()
    }
}

/** 仅供测试替换；应用无需接触此 Provider。 */
internal object PerformanceComponentFactoryProvider {
    @Volatile
    var current: PerformanceComponentFactory = DefaultPerformanceComponentFactory
}
