plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.example.modularconsumer.metrics" }

dependencies {
    implementation("com.example.nativelib:performance-core:1.0.0")
    implementation("com.example.nativelib:performance-transport:1.0.0")
    implementation("com.example.nativelib:performance-metrics:1.0.0")
}
