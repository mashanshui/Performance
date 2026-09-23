package com.shanshui.performance.network

import java.util.concurrent.atomic.AtomicBoolean

/** Jank 专属网络客户端工厂，仅创建 ZIP 上传 API。 */
class JankNetworkClientFactory private constructor(
    /** 共享传输会话。 */
    private val session: TransportSession,
    /** 是否由工厂负责关闭传输会话。 */
    private val ownsSession: Boolean,
) : AutoCloseable {
    /** ZIP 上传客户端。 */
    internal val jankArtifactNetworkClient: JankArtifactNetworkClient
    /** 防止重复关闭共享会话。 */
    private val closed = AtomicBoolean(false)

    /** 使用共享 Retrofit 创建 Jank API 客户端。 */
    init {
        jankArtifactNetworkClient = JankArtifactNetworkClient(
            session.retrofit.create(JankArtifactIngestApi::class.java),
        )
    }

    /** 关闭本工厂拥有的传输资源。 */
    override fun close() {
        if (ownsSession && closed.compareAndSet(false, true)) {
            session.close()
        }
    }

    /** 创建 Jank 网络客户端工厂。 */
    companion object {
        /** 从传输配置创建专属客户端。 */
        fun create(config: NetworkConfig): JankNetworkClientFactory {
            return JankNetworkClientFactory(TransportSession.create(config), ownsSession = true)
        }

        /** 从 SDK 共享会话创建借用型 Jank 客户端，关闭时不释放会话。 */
        fun create(session: TransportSession): JankNetworkClientFactory {
            return JankNetworkClientFactory(session, ownsSession = false)
        }
    }
}
