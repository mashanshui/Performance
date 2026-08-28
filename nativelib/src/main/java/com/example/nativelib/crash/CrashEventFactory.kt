package com.example.nativelib.crash

import com.example.nativelib.network.CrashEvent
import com.example.nativelib.network.CrashPayload
import com.example.nativelib.network.StackFrame
import com.example.nativelib.network.ThrowableNode
import java.util.IdentityHashMap
import java.util.UUID

internal data class CrashDeviceInfo(
    val osVersion: String,
    val deviceModel: String,
)

internal class CrashEventFactory(
    private val config: CrashReporterConfig,
    private val sessionId: String,
    private val anonymousDeviceId: String,
    private val deviceInfo: CrashDeviceInfo,
    private val networkTypeProvider: () -> String?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    fun appStart(): CrashEvent {
        return baseEvent(
            eventId = idGenerator(),
            eventType = EVENT_TYPE_APP_START,
            occurredAt = clock(),
        )
    }

    fun crash(throwable: Throwable): CrashEvent {
        return baseEvent(
            eventId = idGenerator(),
            eventType = EVENT_TYPE_CRASH,
            occurredAt = clock(),
        ).copy(
            crash = CrashPayload(
                kind = "jvm",
                fatal = true,
                throwableChain = ThrowableMapper(config.applicationPackage).map(throwable),
            ),
        )
    }

    private fun baseEvent(
        eventId: String,
        eventType: String,
        occurredAt: Long,
    ): CrashEvent {
        return CrashEvent(
            schemaVersion = config.schemaVersion,
            eventId = eventId.take(MAX_EVENT_ID_LENGTH),
            eventType = eventType,
            occurredAt = occurredAt,
            sessionId = sessionId.take(MAX_SESSION_ID_LENGTH),
            anonymousDeviceId = anonymousDeviceId.take(MAX_DEVICE_ID_LENGTH),
            appId = config.appId.take(MAX_APP_ID_LENGTH),
            appVersion = config.appVersion.take(MAX_TEXT_LENGTH),
            versionCode = config.versionCode,
            buildId = config.buildId.take(MAX_BUILD_ID_LENGTH),
            environment = config.environment.take(MAX_TEXT_LENGTH),
            channel = config.channel.take(MAX_CHANNEL_LENGTH),
            osVersion = deviceInfo.osVersion.take(MAX_TEXT_LENGTH),
            deviceModel = deviceInfo.deviceModel.take(MAX_DEVICE_MODEL_LENGTH),
            networkType = networkTypeProvider()?.take(MAX_NETWORK_TYPE_LENGTH),
        )
    }

    private companion object {
        const val EVENT_TYPE_CRASH = "crash"
        const val EVENT_TYPE_APP_START = "app_start"
        const val MAX_EVENT_ID_LENGTH = 128
        const val MAX_SESSION_ID_LENGTH = 128
        const val MAX_DEVICE_ID_LENGTH = 256
        const val MAX_APP_ID_LENGTH = 128
        const val MAX_BUILD_ID_LENGTH = 256
        const val MAX_CHANNEL_LENGTH = 128
        const val MAX_DEVICE_MODEL_LENGTH = 256
        const val MAX_NETWORK_TYPE_LENGTH = 32
        const val MAX_TEXT_LENGTH = 128
    }
}

internal class ThrowableMapper(
    private val applicationPackage: String,
) {
    fun map(root: Throwable): List<ThrowableNode> {
        val nodes = mutableListOf<ThrowableNode>()
        val visited = IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = root
        var totalFrames = 0

        while (current != null && nodes.size < MAX_THROWABLE_CHAIN && !visited.containsKey(current)) {
            visited[current] = true
            val throwable = current
            val rawFrames = throwable.stackTrace.orEmpty()
            val frames = if (rawFrames.isEmpty()) {
                listOf(syntheticFrame(throwable))
            } else {
                rawFrames
                    .take((MAX_TOTAL_FRAMES - totalFrames).coerceAtLeast(0))
                    .map(::mapFrame)
                    .ifEmpty { listOf(syntheticFrame(throwable)) }
            }
            if (totalFrames + frames.size > MAX_TOTAL_FRAMES) {
                break
            }
            totalFrames += frames.size
            nodes += ThrowableNode(
                type = throwable.javaClass.name.take(MAX_TYPE_LENGTH),
                message = throwable.message
                    ?.let { CrashSanitizer.sanitize(it, MAX_MESSAGE_LENGTH) }
                    ?.takeIf { it.isNotBlank() },
                frames = frames,
            )
            current = throwable.cause
        }

        return nodes.ifEmpty {
            listOf(
                ThrowableNode(
                    type = root.javaClass.name.take(MAX_TYPE_LENGTH),
                    message = root.message?.let { CrashSanitizer.sanitize(it, MAX_MESSAGE_LENGTH) },
                    frames = listOf(syntheticFrame(root)),
                ),
            )
        }
    }

    private fun mapFrame(frame: StackTraceElement): StackFrame {
        val className = frame.className.ifBlank { "<unknown>" }.take(MAX_FRAME_TEXT_LENGTH)
        return StackFrame(
            className = className,
            methodName = frame.methodName.ifBlank { "<unknown>" }.take(MAX_FRAME_TEXT_LENGTH),
            fileName = frame.fileName?.let {
                CrashSanitizer.sanitize(it, MAX_FRAME_TEXT_LENGTH)
            },
            lineNumber = frame.lineNumber.takeIf { it in 0..MAX_LINE_NUMBER } ?: -1,
            applicationFrame = isApplicationFrame(className),
        )
    }

    private fun syntheticFrame(throwable: Throwable): StackFrame {
        return StackFrame(
            className = throwable.javaClass.name.take(MAX_FRAME_TEXT_LENGTH),
            methodName = "<unknown>",
            lineNumber = -1,
            applicationFrame = false,
        )
    }

    private fun isApplicationFrame(className: String): Boolean {
        return className == applicationPackage || className.startsWith("$applicationPackage.")
    }

    private companion object {
        const val MAX_THROWABLE_CHAIN = 16
        const val MAX_TOTAL_FRAMES = 200
        const val MAX_TYPE_LENGTH = 512
        const val MAX_MESSAGE_LENGTH = 4096
        const val MAX_FRAME_TEXT_LENGTH = 512
        const val MAX_LINE_NUMBER = 1_000_000_000
    }
}

internal object CrashSanitizer {
    private val emailPattern = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val phonePattern = Regex("(?<!\\d)\\+?\\d[\\d ()-]{7,}\\d(?!\\d)")
    private val urlPattern = Regex("(?i)\\bhttps?://[^\\s?#]+(?:\\?[^\\s#]*)?")
    private val uuidPattern = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b")
    private val bearerPattern = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val secretAssignmentPattern = Regex(
        "(?i)\\b(cookie|set-cookie|authorization|token|password|secret|api[-_]?key)\\s*[:=]\\s*[^\\s,;]+",
    )

    fun sanitize(value: String, maxLength: Int): String {
        return value
            .replace(emailPattern, "<redacted-email>")
            .replace(phonePattern, "<redacted-phone>")
            .replace(urlPattern, "<redacted-url>")
            .replace(uuidPattern, "<redacted-uuid>")
            .replace(bearerPattern, "Bearer <redacted>")
            .replace(secretAssignmentPattern) { match ->
                "${match.groupValues[1]}=<redacted>"
            }
            .take(maxLength)
    }
}
