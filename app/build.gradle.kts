import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/** 在当前 Gradle 配置阶段生成本次命令共用的编译期 buildId。 */
fun generatePerformanceBuildId(versionName: String, versionCode: Int): String {
    /** 使用 UTC 毫秒时间戳，避免不同构建机器的本地时区影响格式。 */
    val timestamp = ZonedDateTime.now(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
    /** 使用 UUID 的前八位作为随机后缀，降低同一毫秒内的碰撞概率。 */
    val randomSuffix = UUID.randomUUID()
        .toString()
        .replace("-", "")
        .take(8)
    return "$versionName-$versionCode-$timestamp-$randomSuffix"
}

fun buildConfigString(value: String): String {
    return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
}

/** 示例应用的 versionCode，作为编译期 buildId 的固定组成部分。 */
val performanceVersionCode = 1

/** 示例应用的 versionName，作为编译期 buildId 的固定组成部分。 */
val performanceVersionName = "1.0"

/** 当前 Gradle 命令生成并供所有构建变体共用的 buildId。 */
val performanceBuildId = generatePerformanceBuildId(
    versionName = performanceVersionName,
    versionCode = performanceVersionCode,
)

val performanceAppKey = providers.gradleProperty("performance.appKey")
    .orElse("local-demo-app-key")
    .get()

android {
    namespace = "com.example.performance"
    // 仪器烟测直接运行混淆后的 Release 变体。
    testBuildType = "release"
    compileSdk {
        version = release(36)
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.example.performance"
        minSdk = 23
        targetSdk = 36
        versionCode = performanceVersionCode
        versionName = performanceVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PERFORMANCE_APP_KEY", buildConfigString(performanceAppKey))
        buildConfigField("String", "PERFORMANCE_BUILD_ID", buildConfigString(performanceBuildId))
    }

    signingConfigs {
        // Release 构建使用 app 模块下的本地签名文件。
        create("release") {
            // 签名文件路径相对于 app 模块目录解析。
            storeFile = file("sign")
            // 签名文件密码。
            storePassword = "123456"
            // 签名条目别名。
            keyAlias = "key0"
            // 签名条目密码与签名文件密码一致。
            keyPassword = "123456"
        }
    }

    buildTypes {
        release {
            // 将 release 构建绑定到 app/sign 中的 key0 签名条目。
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packagingOptions {
        // Rhea 初始化依赖 ShadowHook；多依赖重复时只保留一个实现，不能排除。
        pickFirst("lib/*/libshadowhook.so")
        pickFirst("lib/*/libshadowhook_nothing.so")
        // apk打包时选择第一个libc++_shared.so，运行时可能遇到不可预知的bug，慎用！
        pickFirst ("lib/*/libc++_shared.so")
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(project(":nativelib"))
    // 示例页面直接调用 RheaTrace3；由 App 显式声明，不依赖 nativelib 的 API 暴露。
    implementation("io.github.mashanshui:rhea-inhouse:1.0.3")
    implementation(libs.androidx.lifecycle.common.jvm)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("io.github.cymchad:BaseRecyclerViewAdapterHelper4:4.3.2")
}
