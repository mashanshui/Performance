plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

// 发布坐标允许通过 Gradle 属性覆盖，方便不同本地消费者复用同一份 AAR。
val publicationGroupId = providers.gradleProperty("nativelib.groupId")
    .orElse("com.example.nativelib")
val publicationArtifactId = providers.gradleProperty("nativelib.artifactId")
    .orElse("nativelib")
val publicationVersion = providers.gradleProperty("nativelib.version")
    .orElse("1.0.0")

group = publicationGroupId.get()
version = publicationVersion.get()

android {
    namespace = "com.shanshui.performance"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 21
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    buildFeatures {
        prefab = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    android {
        packagingOptions {
            // Rhea 初始化依赖 ShadowHook；多依赖重复时只保留一个实现，不能排除。
            pickFirst("lib/*/libshadowhook.so")
            pickFirst("lib/*/libshadowhook_nothing.so")
            pickFirst("lib/*/libc++_shared.so")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

publishing {
    repositories {
        // publishToMavenLocal 默认写入当前用户的 Maven Local 仓库。
        mavenLocal()
    }
    publications {
        register<MavenPublication>("release") {
            groupId = publicationGroupId.get()
            artifactId = publicationArtifactId.get()
            version = publicationVersion.get()
            pom {
                name.set(publicationArtifactId.get())
                description.set("Android 性能工具库")
            }
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.bytedance.android:shadowhook:2.0.0")
//    api("com.blankj:utilcodex:1.31.1")
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.core)
    implementation("com.kuaishou.koom:koom-java-leak:2.2.3")
    implementation("io.github.mashanshui:rhea-inhouse:1.0.3") {
        // 本地开发阶段同版本产物可能被重新发布，确保 Gradle 重新校验 AAR 内容。
        isChanging = true
    }
}
