package com.shanshui.performance.network

import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NetworkClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendBatchAddsHeadersAndSerializesCrashPayload() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "requestId": "request-1",
                      "accepted": 1,
                      "rejected": 0,
                      "duplicate": 0,
                      "retryable": false,
                      "errors": []
                    }
                    """.trimIndent(),
                ),
        )

        val result = runBlocking {
            createClient(schemaVersion = 2).sendBatch(sampleRequest())
        }

        assertTrue(result is NetworkResult.Success)
        val success = result as NetworkResult.Success
        assertEquals(1, success.data.accepted)
        assertEquals(200, success.statusCode)

        val recordedRequest = server.takeRequest()
        assertEquals("POST", recordedRequest.method)
        assertEquals("/api/ingest/v1/batches", recordedRequest.path)
        assertEquals("secret-app-key", recordedRequest.getHeader("X-App-Key"))
        assertEquals("2", recordedRequest.getHeader("X-Schema-Version"))
        assertTrue(recordedRequest.getHeader("Content-Type").orEmpty().startsWith("application/json"))

        val body = JsonParser.parseString(recordedRequest.body.readUtf8()).asJsonObject
        assertEquals("request-1", body.get("requestId").asString)
        val event = body.getAsJsonArray("events")[0].asJsonObject
        assertEquals("crash-1", event.get("eventId").asString)
        assertEquals(
            "11111111-1111-4111-8111-111111111111",
            event.get("processId").asString,
        )
        assertEquals("com.example.performance", event.get("packageName").asString)
        assertFalse(event.has("appId"))
        assertEquals(
            "java.lang.IllegalStateException",
            event.getAsJsonObject("crash")
                .getAsJsonArray("throwableChain")[0].asJsonObject
                .get("type").asString,
        )
    }

    @Test
    fun sendBatchMapsPartialSuccessResponse() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "requestId": "request-1",
                      "accepted": 1,
                      "rejected": 1,
                      "duplicate": 0,
                      "retryable": false,
                      "errors": [{
                        "index": 1,
                        "eventId": "bad-event",
                        "code": "MISSING_SESSION_ID",
                        "message": "sessionId is required",
                        "retryable": false
                      }]
                    }
                    """.trimIndent(),
                ),
        )

        val result = runBlocking {
            createClient().sendBatch(sampleRequest())
        }

        assertTrue(result is NetworkResult.Success)
        val response = (result as NetworkResult.Success).data
        assertEquals(1, response.accepted)
        assertEquals(1, response.rejected)
        assertEquals("MISSING_SESSION_ID", response.errors.single().code)
        assertFalse(response.errors.single().retryable)
    }

    @Test
    fun sendBatchClassifiesRetryableAndPermanentHttpErrors() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid key"))
        val unauthorized = runBlocking { createClient().sendBatch(sampleRequest()) }

        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Retry-After", "42")
                .setBody("temporarily unavailable"),
        )
        val unavailable = runBlocking { createClient().sendBatch(sampleRequest()) }

        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        val rateLimited = runBlocking { createClient().sendBatch(sampleRequest()) }

        assertTrue(unauthorized is NetworkResult.HttpError)
        assertFalse((unauthorized as NetworkResult.HttpError).retryable)
        assertEquals(401, unauthorized.statusCode)

        assertTrue(unavailable is NetworkResult.HttpError)
        assertTrue((unavailable as NetworkResult.HttpError).retryable)
        assertEquals(503, unavailable.statusCode)
        assertEquals(42L, unavailable.retryAfterSeconds)

        assertTrue(rateLimited is NetworkResult.HttpError)
        assertTrue((rateLimited as NetworkResult.HttpError).retryable)
        assertEquals(429, rateLimited.statusCode)
    }

    @Test
    fun sendBatchTreatsOnlyHttp200AsSuccessful() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"requestId\":\"request-1\",\"accepted\":1}"),
        )

        val result = runBlocking { createClient().sendBatch(sampleRequest()) }

        assertTrue(result is NetworkResult.HttpError)
        assertEquals(201, (result as NetworkResult.HttpError).statusCode)
        assertFalse(result.retryable)
    }

    @Test
    fun sendBatchReturnsSerializationErrorForInvalidJson() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{invalid-json"),
        )

        val result = runBlocking {
            createClient().sendBatch(sampleRequest())
        }

        assertTrue(result is NetworkResult.SerializationError)
    }

    @Test
    fun uploadJankArtifactStreamsRawZipWithDedicatedContractHeaders() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"status\":\"accepted\"}"),
        )
        val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x01, 0x02)
        val artifact = File(temporaryFolder.root, "event-1.rheajank.zip")
        artifact.writeBytes(bytes)
        val client = NetworkClientFactory.create(
            NetworkConfig(
                baseUrl = server.url("/api").toString(),
                appKey = "secret-app-key",
                schemaVersion = 7,
            ),
        ).jankArtifactNetworkClient

        val result = runBlocking { client.upload(artifact) }

        assertTrue(result is NetworkResult.Success)
        assertEquals("accepted", (result as NetworkResult.Success).data.status)
        val recordedRequest = server.takeRequest()
        assertEquals("POST", recordedRequest.method)
        assertEquals("/api/ingest/v1/stack-artifacts:parse", recordedRequest.path)
        assertEquals("secret-app-key", recordedRequest.getHeader("X-App-Key"))
        assertEquals(null, recordedRequest.getHeader("X-Schema-Version"))
        assertEquals(null, recordedRequest.getHeader("X-Mapping-Id"))
        assertEquals(
            "application/vnd.shanshui.rheajank+zip",
            recordedRequest.getHeader("Content-Type"),
        )
        assertTrue(recordedRequest.getHeader("Content-Type").orEmpty().contains("multipart").not())
        assertTrue(recordedRequest.body.readByteArray().contentEquals(bytes))
    }

    @Test
    fun uploadJankArtifactReturnsSerializationErrorForInvalidJson() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{invalid-json"),
        )
        val artifact = File(temporaryFolder.root, "event-invalid.rheajank.zip")
        artifact.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        val client = NetworkClientFactory.create(
            NetworkConfig(
                baseUrl = server.url("/").toString(),
                appKey = "secret-app-key",
            ),
        ).jankArtifactNetworkClient

        val result = runBlocking { client.upload(artifact) }

        assertTrue(result is NetworkResult.SerializationError)
    }

    /** 验证 Jank 原始 ZIP 只有 HTTP 200 才进入成功分支。 */
    @Test
    fun uploadJankArtifactTreatsNon200AsHttpError() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"status\":\"accepted\"}"),
        )
        val artifact = File(temporaryFolder.root, "event-created.rheajank.zip")
        artifact.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        val client = NetworkClientFactory.create(
            NetworkConfig(
                baseUrl = server.url("/").toString(),
                appKey = "secret-app-key",
            ),
        ).jankArtifactNetworkClient

        val result = runBlocking { client.upload(artifact) }

        assertTrue(result is NetworkResult.HttpError)
        assertEquals(201, (result as NetworkResult.HttpError).statusCode)
    }

    /** 验证 Jank 的 HTTP 204 空响应不会被误认为上传成功。 */
    @Test
    fun uploadJankArtifactTreatsNoContentAsHttpError() {
        server.enqueue(MockResponse().setResponseCode(204))
        val artifact = File(temporaryFolder.root, "event-no-content.rheajank.zip")
        artifact.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        val client = NetworkClientFactory.create(
            NetworkConfig(
                baseUrl = server.url("/").toString(),
                appKey = "secret-app-key",
            ),
        ).jankArtifactNetworkClient

        val result = runBlocking { client.upload(artifact) }

        assertTrue(result is NetworkResult.HttpError)
        assertEquals(204, (result as NetworkResult.HttpError).statusCode)
    }

    @Test
    fun loggingDoesNotExposeAppKeyOrRequestBody() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"accepted\":1}"),
        )

        val result = runBlocking {
            NetworkClientFactory.create(
                NetworkConfig(
                    baseUrl = server.url("/api").toString(),
                    appKey = "secret-app-key",
                    enableLogging = true,
                ),
            ).crashNetworkClient.sendBatch(sampleRequest())
        }

        assertTrue(result is NetworkResult.Success)
    }

    @Test
    fun networkConfigNormalizesBaseUrlAndRejectsInvalidValues() {
        val config = NetworkConfig(
            baseUrl = server.url("/api").toString().removeSuffix("/"),
            appKey = "key",
        )
        assertNotNull(config.normalizedBaseUrl)
        assertTrue(config.normalizedBaseUrl.toString().endsWith("/api/"))

        assertThrows(IllegalArgumentException::class.java) {
            NetworkConfig(baseUrl = "not a url", appKey = "key")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkConfig(baseUrl = server.url("/").toString(), appKey = " ")
        }
        assertTrue(config.toString().contains("<redacted>"))
        assertFalse(config.toString().contains("key"))
    }

    private fun createClient(
        schemaVersion: Int = 2,
    ): CrashNetworkClient {
        return NetworkClientFactory.create(
            NetworkConfig(
                baseUrl = server.url("/api").toString(),
                appKey = "secret-app-key",
                schemaVersion = schemaVersion,
            ),
        ).crashNetworkClient
    }

    private fun sampleRequest(): CrashBatchRequest {
        return CrashBatchRequest(
            requestId = "request-1",
            events = listOf(
                CrashEvent(
                    schemaVersion = 2,
                    eventId = "crash-1",
                    eventType = "crash",
                    occurredAt = 1_726_000_000_000,
                    sessionId = "session-1",
                    processId = "11111111-1111-4111-8111-111111111111",
                    anonymousDeviceId = "device-1",
                    packageName = "com.example.performance",
                    appVersion = "1.0",
                    versionCode = 1,
                    buildId = "build-1",
                    environment = "debug",
                    channel = "official",
                    osVersion = "35",
                    deviceModel = "Pixel",
                    crash = CrashPayload(
                        throwableChain = listOf(
                            ThrowableNode(
                                type = "java.lang.IllegalStateException",
                                message = "state is invalid",
                                frames = listOf(
                                    StackFrame(
                                        className = "com.example.performance.MainActivity",
                                        methodName = "onCreate",
                                        fileName = "MainActivity.kt",
                                        lineNumber = 42,
                                        applicationFrame = true,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
