plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun buildConfigString(value: String): String {
    return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
}

val performanceAppKey = providers.gradleProperty("performance.appKey")
    .orElse("local-demo-app-key")
    .get()

android {
    namespace = "com.example.performance"
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
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PERFORMANCE_APP_KEY", buildConfigString(performanceAppKey))
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
    implementation(libs.androidx.lifecycle.common.jvm)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("io.github.cymchad:BaseRecyclerViewAdapterHelper4:4.3.2")
}
