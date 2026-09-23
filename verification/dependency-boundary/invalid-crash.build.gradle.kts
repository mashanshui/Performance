// 该文件只用于依赖边界检查器的负例验证，不会作为 Android 模块参与构建。
dependencies {
    // Crash 模块不应直接依赖 Jank 模块。
    implementation(project(":performance-jank"))
}
