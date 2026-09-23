package com.shanshui.performance

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** Application 中可复用的版本和包信息，供所有功能共享。 */
data class ApplicationMetadata(
    /** 当前应用包名。 */
    val packageName: String,
    /** 当前应用版本名。 */
    val versionName: String,
    /** 当前应用版本号。 */
    val versionCode: Int,
) {
    /** 根据既有协议生成默认构建标识。 */
    val defaultBuildId: String
        get() = "$versionName-$versionCode"
}

/** 从 Android Application 解析共享应用元数据。 */
object ApplicationMetadataResolver {
    /** 读取当前应用包信息并转换为稳定的 SDK 元数据。 */
    fun resolve(application: Application): ApplicationMetadata {
        val packageName = application.packageName.trim()
        require(packageName.isNotEmpty()) { "application packageName must not be blank" }
        val packageInfo = getPackageInfo(application.packageManager, packageName)
        val versionName = packageInfo.versionName.orEmpty().ifBlank { "unknown" }
        val versionCode = packageVersionCode(packageInfo)
        return fromValues(packageName, versionName, versionCode)
    }

    /** 纯值解析入口，便于 JVM 测试覆盖版本派生规则。 */
    fun fromValues(
        packageName: String,
        versionName: String,
        versionCode: Long,
    ): ApplicationMetadata {
        require(packageName.isNotBlank()) { "application packageName must not be blank" }
        require(versionName.isNotBlank()) { "application versionName must not be blank" }
        require(versionCode in 0..Int.MAX_VALUE) {
            "application versionCode must be between 0 and ${Int.MAX_VALUE}"
        }
        return ApplicationMetadata(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode.toInt(),
        )
    }

    /** 兼容旧版 Android 的 PackageInfo API。 */
    private fun getPackageInfo(
        packageManager: PackageManager,
        packageName: String,
    ): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
    }

    /** 从 PackageInfo 读取跨 Android 版本兼容的版本号。 */
    private fun packageVersionCode(packageInfo: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }
}
