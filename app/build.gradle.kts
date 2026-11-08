import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import groovy.json.JsonOutput
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/** APK 打包后导出同次构建的标识，便于与 APK、R8 mapping 一起归档。 */
@CacheableTask
abstract class ExportPerformanceBuildInfo : DefaultTask() {
    @get:Input
    abstract val buildId: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun export() {
        val artifacts = checkNotNull(builtArtifactsLoader.get().load(apkDirectory.get())) {
            "未找到 APK 产物元数据，无法导出 buildId"
        }
        // 复用注入 BuildConfig 的值；版本信息从实际产物读取，兼容一个变体输出多个 APK。
        val metadata = linkedMapOf(
            "buildId" to buildId.get(),
            "applicationId" to artifacts.applicationId,
            "variantName" to artifacts.variantName,
            "outputs" to artifacts.elements.map { artifact ->
                linkedMapOf(
                    "outputFile" to File(artifact.outputFile).name,
                    "versionCode" to artifact.versionCode,
                    "versionName" to artifact.versionName,
                )
            },
        )
        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(metadata)) + "\n", Charsets.UTF_8)
    }
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
val performanceVersionCode = providers.gradleProperty("performance.versionCode").map(String::toInt).getOrElse(1)

/** 示例应用的 versionName，作为编译期 buildId 的固定组成部分。 */
val performanceVersionName = providers.gradleProperty("performance.versionName").getOrElse("1.0")

/** 当前 Gradle 命令生成并供所有构建变体共用的 buildId。 */
val performanceBuildId = generatePerformanceBuildId(
    versionName = performanceVersionName,
    versionCode = performanceVersionCode,
)

/** 上报凭据只从本机属性注入，不能硬编码真实值。 */
val performanceAppKey = providers.gradleProperty("performance.appKey")
    .orElse("local-demo-app-key")
    .get()

/** 联调入口仅供本机测试，空值继续使用 SDK 默认地址。 */
val performanceBaseUrl = providers.gradleProperty("performance.baseUrl").getOrElse("")
/** 显式 Debug 联调时关闭无关组件，并允许测试专用启动故障探针。 */
val performanceMemoryJointTest = providers.gradleProperty("performance.memoryJointTest").map(String::toBoolean).getOrElse(false)

android {
    namespace = "com.shanshui.performance"
    // 默认测试混淆后的 Release；可用 -Pperformance.testBuildType=debug 保留本机 Debug 应用数据。
    testBuildType = providers.gradleProperty("performance.testBuildType").orElse("release").get()
    compileSdk {
        version = release(36)
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.shanshui.performance"
        minSdk = 23
        targetSdk = 36
        versionCode = performanceVersionCode
        versionName = performanceVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PERFORMANCE_APP_KEY", buildConfigString(performanceAppKey))
        buildConfigField("String", "PERFORMANCE_BUILD_ID", buildConfigString(performanceBuildId))
        buildConfigField("String", "PERFORMANCE_BASE_URL", buildConfigString(performanceBaseUrl))
        buildConfigField("boolean", "PERFORMANCE_MEMORY_JOINT_TEST", performanceMemoryJointTest.toString())
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

androidComponents {
    onVariants(selector().all()) { variant ->
        val variantTaskName = variant.name.replaceFirstChar { it.uppercaseChar() }
        val exportBuildInfo = tasks.register<ExportPerformanceBuildInfo>("export${variantTaskName}BuildInfo") {
            buildId.set(performanceBuildId)
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            // 独立输出目录避免改写 AGP 元数据，或让生成文件成为 APK 目录的输入。
            outputFile.set(layout.buildDirectory.file("outputs/build-info/${variant.name}/build-info.json"))
        }
        // 监听最终 APK 产物，assemble 和 Android Studio 的 APK 打包均会触发导出。
        variant.artifacts.use(exportBuildInfo)
            .wiredWith(ExportPerformanceBuildInfo::apkDirectory)
            .toListenTo(SingleArtifact.APK)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    // 全量监控入口与原生工具按显式能力分别接入。
    implementation(project(":performance-sdk"))
    implementation(project(":performance-native-tools"))
    implementation(libs.androidx.lifecycle.common.jvm)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Release 仪器测试直接调用 Kotlin 标准库扩展，确保测试 APK 携带其运行时实现。
    androidTestImplementation(kotlin("stdlib"))
    // 4.3.2 使用 Kotlin 2.2 元数据；示例依赖同步到可被 Kotlin 1.9 编译的版本。
    implementation("io.github.cymchad:BaseRecyclerViewAdapterHelper4:4.1.4")
}
