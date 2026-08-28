plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun buildConfigString(value: String): String {
    return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
}

val crashBaseUrl = providers.gradleProperty("crash.baseUrl")
    .orElse("http://192.168.0.150:8080")
    .get()
val crashProjectKey = providers.gradleProperty("crash.projectKey")
    .orElse("local-demo-key")
    .get()
val crashAppId = providers.gradleProperty("crash.appId")
    .orElse("com.example.performance")
    .get()
val crashBuildId = providers.gradleProperty("crash.buildId")
    .orElse("")
    .get()
val crashEnvironment = providers.gradleProperty("crash.environment")
    .orElse("debug")
    .get()
val crashChannel = providers.gradleProperty("crash.channel")
    .orElse("official")
    .get()
val crashEnabled = providers.gradleProperty("crash.enabled")
    .orElse("true")
    .get()
    .toBoolean()
val crashLogging = providers.gradleProperty("crash.logging")
    .orElse("false")
    .get()
    .toBoolean()

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
        buildConfigField("String", "CRASH_BASE_URL", buildConfigString(crashBaseUrl))
        buildConfigField("String", "CRASH_PROJECT_KEY", buildConfigString(crashProjectKey))
        buildConfigField("String", "CRASH_APP_ID", buildConfigString(crashAppId))
        buildConfigField("String", "CRASH_BUILD_ID", buildConfigString(crashBuildId))
        buildConfigField("String", "CRASH_ENVIRONMENT", buildConfigString(crashEnvironment))
        buildConfigField("String", "CRASH_CHANNEL", buildConfigString(crashChannel))
        buildConfigField("Boolean", "CRASH_ENABLED", crashEnabled.toString())
        buildConfigField("Boolean", "CRASH_LOGGING", crashLogging.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
