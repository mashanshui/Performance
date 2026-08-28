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
        crashNetworkClient = CrashNetworkClient(retrofit.create(CrashIngestApi::class.java))
    }

    companion object {
        fun create(config: NetworkConfig): NetworkClientFactory = NetworkClientFactory(config)
    }

    private fun buildOkHttpClient(config: NetworkConfig): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeoutMillis, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .addInterceptor(ProjectKeyInterceptor(config.projectKey, config.schemaVersion))
            .apply {
                if (config.enableLogging) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                            redactHeader(PROJECT_KEY_HEADER)
                            redactHeader("Authorization")
                        },
                    )
                }
            }
            .build()
    }

    private class ProjectKeyInterceptor(
        private val projectKey: String,
        private val schemaVersion: Int,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
                .newBuilder()
                .header(PROJECT_KEY_HEADER, projectKey)
                .header(SCHEMA_VERSION_HEADER, schemaVersion.toString())
                .build()
            return chain.proceed(request)
        }
    }

}

private const val PROJECT_KEY_HEADER = "X-Project-Key"
private const val SCHEMA_VERSION_HEADER = "X-Schema-Version"
