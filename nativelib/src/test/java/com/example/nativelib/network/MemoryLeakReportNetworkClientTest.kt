package com.example.nativelib.network

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryLeakReportNetworkClientTest {
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
    fun uploadSendsMetadataAndReportOnlyAsJsonMultipartParts() {
        val eventId = UUID.randomUUID().toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"eventId":"$eventId","status":"accepted","issueCount":1,"attachmentStatus":"absent"}
                    """.trimIndent(),
                ),
        )
        val reportFile = temporaryFolder.newFile("report.json")
        reportFile.writeText(
            """
            {"runningInfo":{},"gcPaths":[],"classInfos":[],"leakObjects":[]}
            """.trimIndent(),
        )

        val result = runBlocking {
            createClient().upload(sampleMetadata(eventId), reportFile)
        }

        assertTrue(result is NetworkResult.Success)
        assertEquals("accepted", (result as NetworkResult.Success).data.status)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/ingest/v1/memory-reports", request.path)
        assertEquals("secret-app-key", request.getHeader("X-App-Key"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"metadata\"; filename=\"metadata.json\""))
        assertTrue(body.contains("name=\"report\"; filename=\"report.json\""))
        assertTrue(body.contains("Content-Type: application/json"))
        assertTrue(body.contains(eventId))
        assertTrue(body.contains("runningInfo"))
        assertFalse(body.contains("name=\"hprof\""))
    }

    @Test
    fun uploadAcceptsDuplicateResponse() {
        val eventId = UUID.randomUUID().toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    "{" +
                        "\"eventId\":\"$eventId\",\"status\":\"duplicate\",\"issueCount\":1," +
                        "\"attachmentStatus\":\"absent\"}",
                ),
        )
        val reportFile = temporaryFolder.newFile("report.json")
        reportFile.writeText("{\"runningInfo\":{},\"gcPaths\":[],\"classInfos\":[],\"leakObjects\":[]}")

        val result = runBlocking {
            createClient().upload(sampleMetadata(eventId), reportFile)
        }

        assertTrue(result is NetworkResult.Success)
        assertEquals("duplicate", (result as NetworkResult.Success).data.status)
    }

    @Test
    fun uploadClassifies503AsRetryableAndPermanentValidationAsNonRetryable() {
        val eventId = UUID.randomUUID().toString()
        val reportFile = temporaryFolder.newFile("report.json")
        reportFile.writeText("{}")
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Retry-After", "42")
                .setBody("temporarily unavailable"),
        )
        server.enqueue(MockResponse().setResponseCode(400).setBody("invalid report"))

        val client = createClient()
        val unavailable = runBlocking {
            client.upload(sampleMetadata(eventId), reportFile)
        }
        val invalid = runBlocking {
            client.upload(sampleMetadata(UUID.randomUUID().toString()), reportFile)
        }

        assertTrue(unavailable is NetworkResult.HttpError)
        assertTrue((unavailable as NetworkResult.HttpError).retryable)
        assertEquals(42L, unavailable.retryAfterSeconds)
        assertTrue(invalid is NetworkResult.HttpError)
        assertFalse((invalid as NetworkResult.HttpError).retryable)
    }

    private fun createClient(): MemoryLeakReportNetworkClient {
        return NetworkClientFactory.create(
            NetworkConfig(
                baseUrl = server.url("/api").toString(),
                appKey = "secret-app-key",
            ),
        ).memoryLeakReportNetworkClient
    }

    private fun sampleMetadata(eventId: String): MemoryLeakReportMetadata {
        return MemoryLeakReportMetadata(
            schemaVersion = 1,
            eventId = eventId,
            occurredAt = 1_780_000_000_000,
            packageName = "com.example.memoryleak",
            appVersion = "1.0.0",
            versionCode = 1,
            anonymousDeviceId = "device-hash",
            processName = "com.example.memoryleak",
            sessionId = "session-1",
            processId = "11111111-1111-4111-8111-111111111111",
            buildId = "build-1",
            environment = "debug",
            channel = "official",
        )
    }
}
