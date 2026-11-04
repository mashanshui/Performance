plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.shanshui.modularconsumer.full" }

dependencies {
    implementation("com.shanshui.nativelib:performance-sdk:1.0.0")
    implementation("com.shanshui.nativelib:performance-native-tools:1.0.0")
}
