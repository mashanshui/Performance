# Repository Guidelines

## 项目结构与模块组织

- `app/` 是可运行的 Android 应用，界面、演示页面和资源分别位于 `src/main/java` 与 `src/main/res`。
- `nativelib/` 是性能工具库，包含 Kotlin/Java 实现及 `src/main/cpp/` 下的 JNI/CMake 原生代码；当前原生构建限定 `arm64-v8a`。
- 单元测试放在各模块的 `src/test`，设备测试放在 `src/androidTest`。根目录的 `settings.gradle.kts`、`build.gradle.kts` 和 `gradle/libs.versions.toml` 管理模块与依赖。

## 构建、测试和开发命令

在 Windows 上优先使用 Gradle Wrapper，并准备 JDK 11、Android SDK、NDK 与 CMake 3.22.1：

- `.\gradlew.bat assembleDebug`：编译 Debug APK 和库产物。
- `.\gradlew.bat test`：运行所有 JVM 单元测试。
- `.\gradlew.bat :app:testDebugUnitTest`：只运行应用模块的 Debug 单元测试；将 `app` 替换为 `nativelib` 可测试库模块。
- `.\gradlew.bat connectedAndroidTest`：在已连接的设备或模拟器上运行仪器测试。
- `.\gradlew.bat check`：执行项目配置的校验任务，适合提交前快速检查。

## 代码风格与命名约定

Kotlin、Java、C++ 均使用 4 个空格缩进，沿用 Android Studio 默认格式；仓库未配置独立格式化或 lint 规则。包名全部小写，类和 Activity 使用 `PascalCase`，方法、变量使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。资源文件使用小写下划线命名，例如 `activity_main.xml`。性能监控、线程池和 Hook 代码应保持生命周期清晰，并在修改原生接口时同步检查 Kotlin/JNI 两端。

## 测试指南

测试类以 `*Test.kt` 命名；纯逻辑测试放在 `src/test`，需要 Android 环境、UI 或真实设备的测试放在 `src/androidTest`。项目目前没有声明覆盖率门槛；新增功能应至少覆盖核心逻辑和异常路径，涉及 UI 时补充关键交互验证。

## 提交与拉取请求

现有提交使用简短、面向结果的中文主题，例如“帧率检测以及anr消息回朔”“线程池优化”；保持单行、聚焦单一改动，避免无意义的 `update`。拉取请求应说明动机、影响模块、验证命令及设备/Android 版本；UI 变更附截图，JNI、线程或性能变更说明 ABI、基准结果或潜在回归，并关联对应 Issue（如有）。

## 配置与安全提示

`local.properties` 仅保存本机 SDK 路径，不要提交个人路径、密钥或其他凭据。提交前确认构建产物、IDE 文件和临时性能数据未被加入版本控制。
