package com.shanshui.performance.core

import com.shanshui.performance.ApplicationMetadata
import com.shanshui.performance.identity.RuntimeIdentity
import android.app.Application
import java.io.File

/** 所有功能上报共享的身份和应用元数据快照。 */
data class PerformanceEventContext(
    /** 当前进程的 session/process 身份。 */
    val runtimeIdentity: RuntimeIdentity,
    /** 持久化匿名设备标识。 */
    val anonymousDeviceId: String,
    /** 应用包和版本信息。 */
    val applicationMetadata: ApplicationMetadata,
    /** 编译或配置指定的构建标识。 */
    val buildId: String,
    /** 运行环境名称。 */
    val environment: String = "production",
    /** 渠道名称。 */
    val channel: String = "default",
)

/** 仅包含跨功能共享的环境、渠道和构建标识配置。 */
data class SharedMetadataConfig(
    /** 发送给服务端的运行环境。 */
    val environment: String = "debug",
    /** 发送给服务端的渠道。 */
    val channel: String = "official",
    /** 可选构建标识；为空时由应用版本派生。 */
    val buildId: String? = null,
) {
    /** 校验共享元数据字段，避免精简接入依赖某个业务配置触发校验。 */
    init {
        require(environment.isNotBlank() && environment.length <= MAX_ENVIRONMENT_LENGTH) {
            "environment must be non-blank and at most $MAX_ENVIRONMENT_LENGTH characters"
        }
        require(channel.isNotBlank() && channel.length <= MAX_CHANNEL_LENGTH) {
            "channel must be non-blank and at most $MAX_CHANNEL_LENGTH characters"
        }
        require(buildId == null || (buildId.isNotBlank() && buildId.length <= MAX_BUILD_ID_LENGTH)) {
            "buildId must be non-blank and at most $MAX_BUILD_ID_LENGTH characters"
        }
    }

    /** 共享元数据字段的边界常量。 */
    private companion object {
        /** 环境名称最大长度。 */
        const val MAX_ENVIRONMENT_LENGTH = 64
        /** 渠道名称最大长度。 */
        const val MAX_CHANNEL_LENGTH = 128
        /** 构建标识最大长度。 */
        const val MAX_BUILD_ID_LENGTH = 256
    }
}

/** 创建全量和按需接入共用的事件上下文。 */
object PerformanceEventContextFactory {
    /** 从 Application 与持久化设备 ID 文件生成共享上下文。 */
    fun create(
        application: Application,
        metadataConfig: SharedMetadataConfig = SharedMetadataConfig(),
        anonymousDeviceIdFile: File = File(
            application.noBackupFilesDir,
            "performance-crash-reporter/anonymous-device-id",
        ),
    ): PerformanceEventContext {
        val metadata = com.shanshui.performance.ApplicationMetadataResolver.resolve(application)
        val identity = com.shanshui.performance.identity.RuntimeIdentityProvider.current(application)
        val anonymousDeviceId = DeviceIdentityStore(anonymousDeviceIdFile).getOrCreate()
        return PerformanceEventContext(
            runtimeIdentity = identity,
            anonymousDeviceId = anonymousDeviceId,
            applicationMetadata = metadata,
            buildId = metadataConfig.buildId ?: metadata.defaultBuildId,
            environment = metadataConfig.environment,
            channel = metadataConfig.channel,
        )
    }
}
