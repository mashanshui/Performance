import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.JavaVersion
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import java.util.zip.ZipFile

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
}

/** 按模块实际字节码生成精确保留规则，避免共用包名的 App 代码也被整包保留。 */
@CacheableTask
abstract class GenerateSdkKeepRules : DefaultTask() {
    @get:Classpath
    abstract val classJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ListProperty<Directory>

    @get:Input
    abstract val moduleName: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val classNames = sortedSetOf<String>()
        fun collect(path: String) {
            if (path.endsWith(".class") && !path.startsWith("META-INF/")) {
                classNames.add(path.removeSuffix(".class").replace('/', '.'))
            }
        }
        classJars.get().forEach { jar ->
            ZipFile(jar.asFile).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { collect(it.name) }
            }
        }
        classDirectories.get().forEach { directory ->
            directory.asFile.walkTopDown().filter { it.isFile }.forEach {
                collect(it.relativeTo(directory.asFile).invariantSeparatorsPath)
            }
        }
        check(classNames.isNotEmpty()) { "${moduleName.get()} 未找到 SDK 字节码，不能生成空保留规则" }
        val rules = outputFile.get().asFile
        rules.parentFile.mkdirs()
        rules.writeText(buildString {
            appendLine("# 自动生成：保留本 SDK 模块的全部类及成员，禁止混淆、删除和优化。")
            classNames.forEach { appendLine("-keep class $it { *; }") }
            appendLine("-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable")
        })
    }
}

/** 为所有 Android Library 子项目集中设置 SDK、字节码和发布约定。 */
subprojects {
    if (name.startsWith("performance-")) plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = 36
            defaultConfig {
                minSdk = 21
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                consumerProguardFiles("consumer-rules.pro")
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
            buildTypes.configureEach {
                // SDK 的所有构建类型均输出原始类名；宿主仍可独立开启 R8。
                isMinifyEnabled = false
                consumerProguardFiles(layout.buildDirectory.file("generated/sdk-keep/$name/consumer-rules.pro"))
            }
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }
        extensions.configure<LibraryAndroidComponentsExtension> {
            finalizeDsl {
                // 当前仓库没有产品风味；若未来引入，必须同步改为按完整变体注册消费者规则。
                check(it.productFlavors.isEmpty()) { "SDK 保留规则暂不支持产品风味，请同步调整变体规则路径" }
            }
            onVariants(selector().all()) { variant ->
                val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
                val generateRules = tasks.register<GenerateSdkKeepRules>(
                    "generate${variantName}SdkKeepRules",
                ) {
                    moduleName.set(project.name)
                    outputFile.set(layout.buildDirectory.file("generated/sdk-keep/${variant.name}/consumer-rules.pro"))
                }
                // 仅扫描本模块产物，既不保留 App，也不扩大第三方依赖的保留范围。
                variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(generateRules).toGet(
                    ScopedArtifact.CLASSES,
                    GenerateSdkKeepRules::classJars,
                    GenerateSdkKeepRules::classDirectories,
                )
                // AGP 8.13 的 DSL 会把消费者规则转为 File，需显式连接生成任务。
                // export 供 project 依赖使用，merge 供 AAR 打包使用，两条路径均必须先生成规则。
                tasks.matching {
                    it.name == "export${variantName}ConsumerProguardFiles" ||
                        it.name == "merge${variantName}ConsumerProguardFiles" ||
                        (it.name.contains(variantName) && it.name.contains("lint", ignoreCase = true))
                }.configureEach { dependsOn(generateRules) }
            }
        }
    }

    /** 统一新模块发布坐标，允许命令行使用 performance.version 做本轮唯一版本验收。 */
    if (name.startsWith("performance-")) plugins.withId("maven-publish") {
        val performanceGroupId = providers.gradleProperty("performance.groupId")
            .orElse("com.shanshui.nativelib")
        val performanceVersion = providers.gradleProperty("performance.version")
            .orElse("1.0.0")
        group = performanceGroupId.get()
        version = performanceVersion.get()
        extensions.configure<PublishingExtension> {
            repositories {
                mavenLocal()
            }
            publications {
                register<MavenPublication>("release") {
                    groupId = performanceGroupId.get()
                    artifactId = project.name
                    version = performanceVersion.get()
                    pom {
                        name.set(project.name)
                        description.set("Performance SDK 模块")
                    }
                    afterEvaluate {
                        from(components["release"])
                    }
                }
            }
        }
    }
}

/** 允许的模块直接依赖边界；业务模块不得互相依赖或反向依赖 Core/Transport。 */
val performanceDependencyBoundaries = mapOf(
    "performance-core" to emptySet(),
    "performance-transport" to setOf("performance-core"),
    "performance-crash" to setOf("performance-core", "performance-transport"),
    "performance-jank" to setOf("performance-core", "performance-transport"),
    "performance-metrics" to setOf("performance-core", "performance-transport"),
    "performance-leak" to setOf("performance-core", "performance-transport"),
    "performance-native-tools" to setOf("performance-core"),
    "performance-sdk" to setOf(
        "performance-core",
        "performance-transport",
        "performance-crash",
        "performance-jank",
        "performance-metrics",
        "performance-leak",
    ),
)

/** 检查模块构建脚本中的直接 project 依赖和旧单体引用。 */
tasks.register("checkPerformanceModuleDependencies") {
    group = "verification"
    description = "检查 Performance 模块依赖边界，禁止旧 nativelib 和功能模块互相依赖。"
    doLast {
        val projectDependencyPattern = Regex("project\\(\\\":(performance-[^\\\"]+)\\\"\\)")
        performanceDependencyBoundaries.forEach { (moduleName, allowedDependencies) ->
            val buildFile = file("$moduleName/build.gradle.kts")
            check(buildFile.isFile) { "缺少模块构建脚本: $buildFile" }
            val content = buildFile.readText()
            check(!content.contains(":nativelib")) {
                "$moduleName 仍然引用旧 :nativelib"
            }
            val declaredDependencies = projectDependencyPattern.findAll(content)
                .map { it.groupValues[1] }
                .toSet()
            val unexpected = declaredDependencies - allowedDependencies
            check(unexpected.isEmpty()) {
                "$moduleName 存在越界 project 依赖: $unexpected；允许值为 $allowedDependencies"
            }
        }

        // 使用独立 fixture 验证检查器确实能够识别功能模块之间的违规依赖。
        val boundaryFixture = file("verification/dependency-boundary/invalid-crash.build.gradle.kts")
        check(boundaryFixture.isFile) { "缺少依赖边界 fixture: $boundaryFixture" }
        val fixtureDependencies = projectDependencyPattern.findAll(boundaryFixture.readText())
            .map { it.groupValues[1] }
            .toSet()
        val fixtureUnexpected = fixtureDependencies - performanceDependencyBoundaries.getValue("performance-crash")
        check(fixtureUnexpected == setOf("performance-jank")) {
            "依赖边界 fixture 未识别为预期违规: $fixtureUnexpected"
        }
    }
}

/** 把依赖边界检查接入根校验任务，避免只运行单模块测试时遗漏结构约束。 */
tasks.matching { it.name == "check" }.configureEach {
    dependsOn("checkPerformanceModuleDependencies")
}
