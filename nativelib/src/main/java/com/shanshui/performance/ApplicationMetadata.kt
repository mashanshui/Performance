package com.shanshui.performance

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** Application 中可复用的版本和包信息，便于初始化编排与 JVM 测试解耦。 */
internal data class ApplicationMetadata(
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
) {
    val defaultBuildId: String
        get() = "$versionName-$versionCode"
}

internal object ApplicationMetadataResolver {
    fun resolve(application: Application): ApplicationMetadata {
        val packageName = application.packageName.trim()
        require(packageName.isNotEmpty()) { "application packageName must not be blank" }
        val packageInfo = getPackageInfo(application.packageManager, packageName)
        val versionName = packageInfo.versionName.orEmpty().ifBlank { "unknown" }
        val versionCode = packageVersionCode(packageInfo)
        return fromValues(packageName, versionName, versionCode)
    }

    /** 纯值解析入口，避免版本派生规则依赖 Android 运行时。 */
    internal fun fromValues(
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

    private fun packageVersionCode(packageInfo: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }
}
