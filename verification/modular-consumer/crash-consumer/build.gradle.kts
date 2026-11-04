plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.shanshui.modularconsumer.crash" }

dependencies {
    implementation("com.shanshui.nativelib:performance-core:1.0.0")
    implementation("com.shanshui.nativelib:performance-transport:1.0.0")
    implementation("com.shanshui.nativelib:performance-crash:1.0.0")
}
