package com.example.nativelib.network

import com.google.gson.GsonBuilder
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class NetworkClientFactory private constructor(
    config: NetworkConfig,
) {
    val okHttpClient: OkHttpClient
    val retrofit: Retrofit
    val crashNetworkClient: CrashNetworkClient
    val jankArtifactNetworkClient: JankArtifactNetworkClient
    internal val fpsNetworkClient: FpsNetworkClient
    internal val memoryNetworkClient: MemoryNetworkClient

    init {
        okHttpClient = buildOkHttpClient(config)
        retrofit = Retrofit.Builder()
            .baseUrl(config.normalizedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(
                GsonConverterFactory.create(
                    GsonBuilder().disableHtmlEscaping().create(),
                ),
            )
            .build()
        crashNetworkClient = CrashNetworkClient(
            retrofit.create(CrashIngestApi::class.java),
            config.schemaVersion,
        )
        jankArtifactNetworkClient = JankArtifactNetworkClient(
            retrofit.create(JankArtifactIngestApi::class.java),
        )
        fpsNetworkClient = FpsNetworkClient(
            retrofit.create(FpsIngestApi::class.java),
        )
        memoryNetworkClient = MemoryNetworkClient(
            retrofit.create(MemoryIngestApi::class.java),
        )
    }

    companion object {
        fun create(config: NetworkConfig): NetworkClientFactory = NetworkClientFactory(config)
    }

    /** 统一 SDK 关闭或初始化回滚时释放 OkHttp 自建资源。 */
    internal fun close() {
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
        okHttpClient.cache?.close()
    }

    private fun buildOkHttpClient(config: NetworkConfig): OkHttpClient {
        return OkHttpClient.Builder()
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
    }

    private class AppKeyInterceptor(
        private val appKey: String,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
                .newBuilder()
                .header(APP_KEY_HEADER, appKey)
                .build()
            return chain.proceed(request)
        }
    }

}

private const val APP_KEY_HEADER = "X-App-Key"
