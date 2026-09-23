package com.shanshui.performance.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Transport 自建与外部资源所有权边界测试。 */
class TransportSessionTest {
    /** 验证外部 OkHttp/Retrofit 必须成对传入。 */
    @Test
    fun externalResourcesMustBeProvidedTogether() {
        val config = NetworkConfig("http://localhost", "key")
        assertThrows(IllegalArgumentException::class.java) {
            TransportSession.create(config, externalClient = OkHttpClient())
        }
    }

    /** 验证借用外部资源时关闭会话不会主动关闭外部连接池。 */
    @Test
    fun externalResourcesAreBorrowed() {
        val client = OkHttpClient()
        val retrofit = Retrofit.Builder().baseUrl("http://localhost/").client(client).build()
        val session = TransportSession.create(
            NetworkConfig("http://localhost", "key"),
            externalClient = client,
            externalRetrofit = retrofit,
        )
        session.close()
        assertFalse(client.dispatcher.executorService.isShutdown)
        client.dispatcher.executorService.shutdown()
    }
}
