package com.shanshui.performance.network

import java.util.concurrent.atomic.AtomicBoolean

/** Metrics 专属网络客户端工厂，只创建 FPS 和内存 API。 */
class MetricsNetworkClientFactory private constructor(
    /** 共享传输会话。 */
    private val session: TransportSession,
    /** 是否由工厂负责关闭传输会话。 */
    private val ownsSession: Boolean,
) : AutoCloseable {
    /** FPS 请求客户端。 */
    internal val fpsNetworkClient: FpsNetworkClient
    /** 内存指标请求客户端。 */
    internal val memoryNetworkClient: MemoryNetworkClient
    /** 是否已经关闭。 */
    private val closed = AtomicBoolean(false)

    /** 使用共享 Retrofit 创建两个 Metrics API 客户端。 */
    init {
        fpsNetworkClient = FpsNetworkClient(
            session.retrofit.create(FpsIngestApi::class.java),
        )
        memoryNetworkClient = MemoryNetworkClient(
            session.retrofit.create(MemoryIngestApi::class.java),
        )
    }

    /** 关闭由该工厂创建的共享传输资源。 */
    override fun close() {
        if (ownsSession && closed.compareAndSet(false, true)) {
            session.close()
        }
    }

    /** 创建 Metrics 网络客户端工厂。 */
    companion object {
        /** 从传输配置创建 Metrics 专属客户端。 */
        fun create(config: NetworkConfig): MetricsNetworkClientFactory {
            return MetricsNetworkClientFactory(TransportSession.create(config), ownsSession = true)
        }

        /** 从 SDK 共享会话创建借用型 Metrics 客户端，关闭时不释放会话。 */
        fun create(session: TransportSession): MetricsNetworkClientFactory {
            return MetricsNetworkClientFactory(session, ownsSession = false)
        }
    }
}
