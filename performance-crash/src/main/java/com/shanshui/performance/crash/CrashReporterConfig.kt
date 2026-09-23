package com.shanshui.performance.crash

internal data class CrashReporterConfig(
    val packageName: String,
    val appVersion: String,
    val versionCode: Int,
    val buildId: String,
    val applicationPackage: String = packageName,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(appVersion.isNotBlank()) { "appVersion must not be blank" }
        require(versionCode >= 0) { "versionCode must be non-negative" }
        require(buildId.isNotBlank()) { "buildId must not be blank" }
        require(applicationPackage.isNotBlank()) { "applicationPackage must not be blank" }
    }
}
