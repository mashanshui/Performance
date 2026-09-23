plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}

/** 统一独立消费者的 Android SDK、字节码和 Release 混淆边界。 */
subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            compileSdk = 36
            signingConfigs {
                create("consumer") {
                    // 仅用于本地验证 APK，复用仓库示例的测试签名，不作为发布凭据。
                    storeFile = rootProject.file("../../app/sign")
                    storePassword = "123456"
                    keyAlias = "key0"
                    keyPassword = "123456"
                }
            }
            defaultConfig {
                minSdk = 21
                targetSdk = 36
                versionCode = 1
                versionName = "1.0"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
            packaging {
                jniLibs {
                    // 多个原生组件携带相同的 C++ 运行库时选择同一份文件。
                    pickFirsts += "lib/*/libc++_shared.so"
                }
            }
            buildTypes {
                debug {
                    signingConfig = signingConfigs.getByName("consumer")
                }
                release {
                    signingConfig = signingConfigs.getByName("consumer")
                    isMinifyEnabled = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                }
            }
        }
    }
    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
            compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}
