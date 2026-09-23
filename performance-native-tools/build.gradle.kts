plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.shanshui.performance.native_tools"

    buildFeatures {
        prefab = true
    }

    defaultConfig {
        ndk {
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packagingOptions {
        jniLibs.pickFirsts.add("lib/*/libshadowhook.so")
        jniLibs.pickFirsts.add("lib/*/libshadowhook_nothing.so")
        jniLibs.pickFirsts.add("lib/*/libc++_shared.so")
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(project(":performance-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation("com.bytedance.android:shadowhook:2.0.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
