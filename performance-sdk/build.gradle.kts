plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.shanshui.performance.sdk"

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(project(":performance-core"))
    api(project(":performance-transport"))
    api(project(":performance-crash"))
    api(project(":performance-jank"))
    api(project(":performance-metrics"))
    api(project(":performance-leak"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
