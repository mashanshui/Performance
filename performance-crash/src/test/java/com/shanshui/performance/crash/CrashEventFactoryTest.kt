package com.shanshui.performance.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashEventFactoryTest {
    @Test
    fun crashEventSanitizesMessageAndLimitsThrowablePayload() {
        val factory = CrashEventFactory(
            config = testConfig(),
            serviceConfig = testServiceConfig(),
            sessionId = "session-1",
            processId = "11111111-1111-4111-8111-111111111111",
            anonymousDeviceId = "install-1",
            deviceInfo = CrashDeviceInfo("35", "Pixel Test"),
            networkTypeProvider = { "wifi" },
            clock = { 1_726_000_000_000 },
            idGenerator = { "event-1" },
        )
        val root = IllegalStateException(
            "contact alice@example.com, token=secret-value, " +
                "https://example.test/path?user=alice&token=secret",
        )
        root.stackTrace = Array(220) { index ->
            StackTraceElement(
                "com.example.performance.Screen$index",
                "render",
                "Screen.kt",
                index,
            )
        }

        val event = factory.crash(root)
        val crash = event.crash

        assertEquals("crash", event.eventType)
        assertEquals(2, event.schemaVersion)
        assertEquals("com.example.performance", event.packageName)
        assertEquals(1_726_000_000_000, event.occurredAt)
        assertEquals("wifi", event.networkType)
        assertNotNull(crash)
        assertTrue(crash!!.fatal)
        assertEquals("jvm", crash.kind)
        assertTrue(crash.throwableChain.size <= 16)
        assertTrue(crash.throwableChain.sumOf { it.frames.size } <= 200)
        assertFalse(crash.throwableChain.first().message.orEmpty().contains("alice@example.com"))
        assertFalse(crash.throwableChain.first().message.orEmpty().contains("secret-value"))
        assertFalse(crash.throwableChain.first().message.orEmpty().contains("https://"))
        assertTrue(crash.throwableChain.first().frames.first().applicationFrame == true)
    }

    @Test
    fun appStartEventHasNoCrashPayloadAndUsesCommonSessionMetadata() {
        val factory = CrashEventFactory(
            config = testConfig(),
            serviceConfig = testServiceConfig(),
            sessionId = "session-1",
            processId = "11111111-1111-4111-8111-111111111111",
            anonymousDeviceId = "install-1",
            deviceInfo = CrashDeviceInfo("35", "Pixel Test"),
            networkTypeProvider = { null },
            clock = { 123L },
            idGenerator = { "start-1" },
        )

        val event = factory.appStart()

        assertEquals("app_start", event.eventType)
        assertEquals("session-1", event.sessionId)
        assertEquals("11111111-1111-4111-8111-111111111111", event.processId)
        assertEquals("install-1", event.anonymousDeviceId)
        assertEquals(123L, event.occurredAt)
        assertTrue(event.crash == null)
    }

    private fun testConfig(): CrashReporterConfig {
        return CrashReporterConfig(
            packageName = "com.example.performance",
            appVersion = "1.0",
            versionCode = 1,
            buildId = "build-1",
            applicationPackage = "com.example.performance",
        )
    }

    private fun testServiceConfig(): CrashServiceConfig {
        return CrashServiceConfig(
            baseUrl = "https://example.test/",
            appKey = "test-app-key",
            environment = "test",
            channel = "unit-test",
            schemaVersion = 2,
            enableNetworkLogging = false,
            connectTimeoutMillis = 3_000L,
            readTimeoutMillis = 3_000L,
            writeTimeoutMillis = 3_000L,
            crashBatchSize = 20,
            crashMaxBatchBytes = 512 * 1024,
            crashUploadIntervalMillis = 30_000L,
        )
    }
}
