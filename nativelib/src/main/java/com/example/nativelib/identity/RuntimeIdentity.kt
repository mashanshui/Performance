package com.example.nativelib.identity

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import java.util.UUID

/** 当前 Android 进程在本次进程生命周期内共享的运行身份。 */
internal data class RuntimeIdentity(
    /** 当前应用进程启动实例的唯一标识。 */
    val sessionId: String,
    /** 当前 Android 进程的唯一标识；主进程与 sessionId 相同。 */
    val processId: String,
) {
    init {
        require(UuidV4.isCanonical(sessionId)) { "sessionId must be a canonical UUID v4" }
        require(UuidV4.isCanonical(processId)) { "processId must be a canonical UUID v4" }
    }
}

/** UUID v4 的严格校验工具，保证发送给服务端的是规范小写字符串。 */
internal object UuidV4 {
    /** 判断字符串是否为规范形式的 UUID v4。 */
    internal fun isCanonical(value: String): Boolean {
        // 解析候选字符串，无法解析时直接判定为非法。
        val uuid = runCatching { UUID.fromString(value) }.getOrNull() ?: return false
        return uuid.version() == 4 &&
            uuid.variant() == 2 &&
            uuid.toString() == value
    }
}

/** 为同一 Android 进程缓存 sessionId/processId，避免各 Reporter 各自生成身份。 */
internal object RuntimeIdentityProvider {
    /** 保证首次初始化的身份创建与进程内读取安全。 */
    private val lock = Any()

    /** 当前进程已创建的运行身份；SDK close 后仍保持不变。 */
    @Volatile
    private var cached: RuntimeIdentity? = null

    /** 返回当前进程共享的运行身份。 */
    internal fun current(application: Application): RuntimeIdentity {
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: create(application).also { created -> cached = created }
        }
    }

    /** 创建测试身份，覆盖主进程和子进程的 processId 规则。 */
    internal fun createForTesting(
        isMainProcess: Boolean,
        uuidGenerator: () -> String,
    ): RuntimeIdentity {
        // 测试生成的会话身份。
        val sessionId = uuidGenerator()
        // 主进程复用 sessionId，子进程生成独立的 processId。
        val processId = if (isMainProcess) sessionId else uuidGenerator()
        return RuntimeIdentity(sessionId = sessionId, processId = processId)
    }

    /** 清除进程级缓存，仅供 JVM 测试隔离使用。 */
    internal fun resetForTesting() {
        synchronized(lock) {
            cached = null
        }
    }

    /** 按当前进程名生成主进程或子进程身份。 */
    private fun create(application: Application): RuntimeIdentity {
        // 当前 Android 进程启动实例的会话身份。
        val sessionId = UUID.randomUUID().toString()
        // 子进程不与主进程共享 processId，主进程沿用 sessionId。
        val processId = if (isMainProcess(application)) sessionId else UUID.randomUUID().toString()
        return RuntimeIdentity(sessionId = sessionId, processId = processId)
    }

    /** 判断当前 Android 进程是否为应用主进程。 */
    private fun isMainProcess(application: Application): Boolean {
        // 当前 Android 进程的系统名称。
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            // Android P 之前通过进程状态读取当前进程名。
            val processInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(processInfo)
            processInfo.processName
        }
        return application.packageName == processName
    }
}
