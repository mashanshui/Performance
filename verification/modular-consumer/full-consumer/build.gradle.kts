plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.example.modularconsumer.full" }

dependencies {
    implementation("com.example.nativelib:performance-sdk:1.0.0")
    implementation("com.example.nativelib:performance-native-tools:1.0.0")
}
