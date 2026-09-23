package com.shanshui.performance.network

import com.google.gson.GsonBuilder
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/** 仅提供共享 OkHttp/Retrofit 设施，不创建任何业务 API、DTO 或上传客户端。 */
class TransportSession private constructor(
    /** 本次会话使用的网络配置。 */
    val config: NetworkConfig,
    /** 由本会话创建并供功能模块复用的 OkHttp 客户端。 */
    val okHttpClient: OkHttpClient,
    /** 由本会话创建并供功能模块创建 API 的 Retrofit 实例。 */
    val retrofit: Retrofit,
    /** 是否由本会话拥有并负责关闭 HTTP 资源。 */
    private val ownsResources: Boolean = true,
    /** 会话是否已经关闭。 */
    private val closed: AtomicBoolean = AtomicBoolean(false),
) : AutoCloseable {
    /** 关闭本会话自建的调度器、连接池和缓存，重复调用安全。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        if (!ownsResources) {
            return
        }
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
        okHttpClient.cache?.close()
    }

    /** 创建共享传输会话；宿主传入的外部客户端不由此类管理。 */
    companion object {
        /** 按网络配置创建带认证和超时策略的共享会话。 */
        fun create(
            config: NetworkConfig,
            externalClient: OkHttpClient? = null,
            externalRetrofit: Retrofit? = null,
        ): TransportSession {
            require((externalClient == null) == (externalRetrofit == null)) {
                "externalClient and externalRetrofit must be provided together"
            }
            if (externalClient != null && externalRetrofit != null) {
                return TransportSession(
                    config = config,
                    okHttpClient = externalClient,
                    retrofit = externalRetrofit,
                    ownsResources = false,
                )
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(config.writeTimeoutMillis, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .addInterceptor(AppKeyInterceptor(config.appKey))
                .apply {
                    if (config.enableLogging) {
                        addInterceptor(
                            HttpLoggingInterceptor().apply {
                                level = HttpLoggingInterceptor.Level.BASIC
                                redactHeader(APP_KEY_HEADER)
                                redactHeader("Authorization")
                            },
                        )
                    }
                }
                .build()
            val retrofit = Retrofit.Builder()
                .baseUrl(config.normalizedBaseUrl)
                .client(client)
                .addConverterFactory(
                    GsonConverterFactory.create(
                        GsonBuilder().disableHtmlEscaping().create(),
                    ),
                )
                .build()
            return TransportSession(config, client, retrofit)
        }
    }

    /** 为所有业务请求写入统一 App Key 请求头。 */
    private class AppKeyInterceptor(
        /** 当前会话的应用凭据。 */
        private val appKey: String,
    ) : Interceptor {
        /** 添加认证头后继续执行 OkHttp 调用链。 */
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
                .newBuilder()
                .header(APP_KEY_HEADER, appKey)
                .build()
            return chain.proceed(request)
        }
    }
}

/** SDK 统一的 App Key 请求头名称。 */
private const val APP_KEY_HEADER = "X-App-Key"
