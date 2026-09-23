package com.shanshui.performance.network

import java.util.concurrent.atomic.AtomicBoolean

/** Leak 专属网络客户端工厂，仅创建 report multipart API。 */
class LeakNetworkClientFactory private constructor(
    /** 共享传输会话。 */
    private val session: TransportSession,
    /** 是否由工厂负责关闭传输会话。 */
    private val ownsSession: Boolean,
) : AutoCloseable {
    /** report multipart 上传客户端。 */
    internal val memoryLeakReportNetworkClient: MemoryLeakReportNetworkClient
    /** 防止重复关闭。 */
    private val closed = AtomicBoolean(false)

    /** 使用共享 Retrofit 创建 report API 客户端。 */
    init {
        memoryLeakReportNetworkClient = MemoryLeakReportNetworkClient(
            session.retrofit.create(MemoryLeakReportIngestApi::class.java),
        )
    }

    /** 关闭本工厂拥有的传输会话。 */
    override fun close() {
        if (ownsSession && closed.compareAndSet(false, true)) {
            session.close()
        }
    }

    /** 创建 Leak 网络客户端。 */
    companion object {
        /** 从基础传输配置创建客户端。 */
        fun create(config: NetworkConfig): LeakNetworkClientFactory {
            return LeakNetworkClientFactory(TransportSession.create(config), ownsSession = true)
        }

        /** 从 SDK 共享会话创建借用型 Leak 客户端，关闭时不释放会话。 */
        fun create(session: TransportSession): LeakNetworkClientFactory {
            return LeakNetworkClientFactory(session, ownsSession = false)
        }
    }
}
