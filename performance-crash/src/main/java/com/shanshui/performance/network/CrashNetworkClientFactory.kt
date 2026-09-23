package com.shanshui.performance.network

import java.util.concurrent.atomic.AtomicBoolean

/** Crash 专属网络客户端工厂，仅创建 Crash API。 */
class CrashNetworkClientFactory private constructor(
    /** 共享传输会话。 */
    private val session: TransportSession,
    /** 协议版本。 */
    schemaVersion: Int,
    /** 是否由工厂负责关闭传输会话。 */
    private val ownsSession: Boolean,
) : AutoCloseable {
    /** Crash 批次请求客户端。 */
    internal val crashNetworkClient: CrashNetworkClient
    /** 防止重复关闭。 */
    private val closed = AtomicBoolean(false)

    /** 使用共享 Retrofit 创建 Crash API 客户端。 */
    init {
        crashNetworkClient = CrashNetworkClient(
            session.retrofit.create(CrashIngestApi::class.java),
            schemaVersion,
        )
    }

    /** 关闭本工厂拥有的传输会话。 */
    override fun close() {
        if (ownsSession && closed.compareAndSet(false, true)) {
            session.close()
        }
    }

    /** 创建 Crash 网络客户端。 */
    companion object {
        /** 从网络配置创建 Crash 客户端。 */
        fun create(config: NetworkConfig): CrashNetworkClientFactory {
            return CrashNetworkClientFactory(
                session = TransportSession.create(config),
                schemaVersion = config.schemaVersion,
                ownsSession = true,
            )
        }

        /** 从 SDK 共享会话创建借用型 Crash 客户端，关闭时不释放会话。 */
        fun create(session: TransportSession, schemaVersion: Int): CrashNetworkClientFactory {
            return CrashNetworkClientFactory(
                session = session,
                schemaVersion = schemaVersion,
                ownsSession = false,
            )
        }
    }
}
