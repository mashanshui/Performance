package com.shanshui.performance

import com.shanshui.performance.crash.CrashConfig as ModuleCrashConfig
import com.shanshui.performance.jank.JankConfig as ModuleJankConfig
import com.shanshui.performance.memory.leak.MemoryLeakConfig as ModuleMemoryLeakConfig
import com.shanshui.performance.metrics.FpsConfig as ModuleFpsConfig
import com.shanshui.performance.metrics.FpsLogLevel as ModuleFpsLogLevel
import com.shanshui.performance.metrics.MemoryConfig as ModuleMemoryConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 聚合入口使用的 Crash 配置别名；具体实现归属 Crash 模块。 */
typealias CrashConfig = ModuleCrashConfig

/** 聚合入口使用的 Jank 配置别名；具体实现归属 Jank 模块。 */
typealias JankConfig = ModuleJankConfig

/** 聚合入口使用的 FPS 配置别名；具体实现归属 Metrics 模块。 */
typealias FpsConfig = ModuleFpsConfig

/** 聚合入口使用的 FPS 日志等级别名。 */
typealias FpsLogLevel = ModuleFpsLogLevel

/** 聚合入口使用的内存配置别名；具体实现归属 Metrics 模块。 */
typealias MemoryConfig = ModuleMemoryConfig

/** 聚合入口使用的 Activity 泄漏配置别名；具体实现归属 Leak 模块。 */
typealias MemoryLeakConfig = ModuleMemoryLeakConfig

/** 全量 SDK 的服务地址、认证之外的共享网络参数。App Key 仍由初始化方法单独传入。 */
data class ServiceConfig(
    /** 事件服务基础地址。 */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** 协议环境。 */
    val environment: String = DEFAULT_ENVIRONMENT,
    /** 协议渠道。 */
    val channel: String = DEFAULT_CHANNEL,
    /** 事件协议版本。 */
    val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
    /** 是否启用基础网络日志。 */
    val enableNetworkLogging: Boolean = false,
    /** 建连超时时间。 */
    val connectTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** 读取超时时间。 */
    val readTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** 写入超时时间。 */
    val writeTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** 全量事件共享构建标识。 */
    val buildId: String? = null,
) {
    /** 校验服务参数，避免功能模块分别解析同一地址。 */
    init {
        val parsed = baseUrl.trim().toHttpUrlOrNull()
        require(parsed != null && (parsed.scheme == "http" || parsed.scheme == "https")) {
            "baseUrl must be a valid http(s) URL"
        }
        require(parsed.query == null && parsed.fragment == null) {
            "baseUrl must not contain a query or fragment"
        }
        require(environment.isNotBlank() && environment.length <= 64) {
            "environment must be non-blank and at most 64 characters"
        }
        require(channel.isNotBlank() && channel.length <= 128) {
            "channel must be non-blank and at most 128 characters"
        }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        require(writeTimeoutMillis > 0) { "writeTimeoutMillis must be positive" }
        require(buildId == null || (buildId.isNotBlank() && buildId.length <= 256)) {
            "buildId must be non-blank and at most 256 characters"
        }
    }

    /** 转换为 Transport 使用的配置，并把 App Key 限制在传输层。 */
    internal fun toNetworkConfig(appKey: String): com.shanshui.performance.network.NetworkConfig {
        return com.shanshui.performance.network.NetworkConfig(
            baseUrl = baseUrl.trim(),
            appKey = appKey.trim(),
            schemaVersion = schemaVersion,
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            writeTimeoutMillis = writeTimeoutMillis,
            enableLogging = enableNetworkLogging,
        )
    }

    /** 服务配置默认值。 */
    companion object {
        /** 默认事件服务地址。 */
//        const val DEFAULT_BASE_URL = "http://124.221.252.121:80"
//        const val DEFAULT_BASE_URL = "http://192.168.0.151:8080"
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8080"
        /** 默认环境。 */
        const val DEFAULT_ENVIRONMENT = "debug"
        /** 默认渠道。 */
        const val DEFAULT_CHANNEL = "official"
        /** 默认协议版本。 */
        const val DEFAULT_SCHEMA_VERSION = 2
        /** 默认网络超时。 */
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L
    }
}

/** 全量 SDK 的组合配置；各功能参数仍由所属模块定义和校验。 */
data class PerformanceConfig(
    /** 服务地址、环境和共享网络参数。 */
    val service: ServiceConfig = ServiceConfig(),
    /** Crash 配置。 */
    val crash: CrashConfig = CrashConfig(),
    /** Jank 配置。 */
    val jank: JankConfig = JankConfig(),
    /** FPS 配置。 */
    val fps: FpsConfig = FpsConfig(),
    /** 内存指标配置。 */
    val memory: MemoryConfig = MemoryConfig(),
    /** Activity 泄漏配置。 */
    val memoryLeak: MemoryLeakConfig = MemoryLeakConfig(),
)
