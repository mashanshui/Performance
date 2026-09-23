plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.shanshui.performance.leak"

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(project(":performance-core"))
    api(project(":performance-transport"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.koom.java.leak)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
