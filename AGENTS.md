# 项目协作指南

## 知识库入口

- [知识库导航与阅读路径](docs/knowledge-base/README.md)
- [项目概览与能力状态](docs/knowledge-base/00-项目概览与能力状态.md)
- [模块架构与源码导航](docs/knowledge-base/01-模块架构与源码导航.md)
- [服务端对接与数据协议](docs/knowledge-base/09-服务端对接与数据协议.md)
- [常见问题与待验证事项](docs/knowledge-base/12-常见问题与待验证事项.md)
- [SDK 配置指南](nativelib/CONFIGURATION.md)与[FPS 算法说明](nativelib/FPS_ALGORITHM.md)

根目录以本文件作为项目规范和知识库入口，不另设根目录 README.md。

## 项目结构与模块组织

- `app/` 是可运行的 Android 应用，界面、演示页面和资源分别位于 `src/main/java` 与 `src/main/res`。
- `nativelib/` 是性能工具库，包含 Kotlin/Java 实现及 `src/main/cpp/` 下的 JNI/CMake 原生代码；当前原生构建限定 `arm64-v8a`。
- 单元测试放在各模块的 `src/test`，设备测试放在 `src/androidTest`。根目录的 `settings.gradle.kts`、`build.gradle.kts` 和 `gradle/libs.versions.toml` 管理模块与依赖。

## 构建、测试和开发命令

在 Windows 上优先使用 Gradle Wrapper，并准备可运行当前 Gradle/AGP 的 JDK、Android SDK、NDK 与 CMake 3.22.1。当前 AGP 8.13.2 的最低运行 JDK 为 17，模块 Java/Kotlin 字节码目标仍为 11；版本依据和依赖准备见[构建测试与本地发布](docs/knowledge-base/11-构建测试与本地发布.md)。

- `.\gradlew.bat assembleDebug`：编译 Debug APK 和库产物。
- `.\gradlew.bat test`：运行所有 JVM 单元测试。
- `.\gradlew.bat :app:testDebugUnitTest`：只运行应用模块的 Debug 单元测试；将 `app` 替换为 `nativelib` 可测试库模块。
- `.\gradlew.bat connectedAndroidTest`：在已连接的设备或模拟器上运行仪器测试。
- `.\gradlew.bat check`：执行项目配置的校验任务，适合提交前快速检查。

## 代码风格与命名约定

Kotlin、Java、C++ 均使用 4 个空格缩进，沿用 Android Studio 默认格式；仓库未配置独立格式化或 lint 规则。包名全部小写，类和 Activity 使用 `PascalCase`，方法、变量使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。资源文件使用小写下划线命名，例如 `activity_main.xml`。性能监控、线程池和 Hook 代码应保持生命周期清晰，并在修改原生接口时同步检查 Kotlin/JNI 两端。

生成代码加入必要注释，生成文档全部使用中文。项目处于初次开发阶段，修改功能时可直接调整当前设计，不需要维护旧代码兼容层。

## 测试指南

测试类以 `*Test.kt` 命名；纯逻辑测试放在 `src/test`，需要 Android 环境、UI 或真实设备的测试放在 `src/androidTest`。项目目前没有声明覆盖率门槛；新增功能应至少覆盖核心逻辑和异常路径，涉及 UI 时补充关键交互验证。

## 提交与拉取请求

现有提交使用简短、面向结果的中文主题，例如“帧率检测以及anr消息回朔”“线程池优化”；保持单行、聚焦单一改动，避免无意义的 `update`。拉取请求应说明动机、影响模块、验证命令及设备/Android 版本；UI 变更附截图，JNI、线程或性能变更说明 ABI、基准结果或潜在回归，并关联对应 Issue（如有）。

## 配置与安全提示

`local.properties` 仅保存本机 SDK 路径，不要提交个人路径、密钥或其他凭据。提交前确认构建产物、IDE 文件和临时性能数据未被加入版本控制。

## 知识库维护规则

- 每次修改项目后，必须评估改动是否影响知识库中的功能说明、执行链路、配置、协议、构建测试或能力状态；如有影响，必须在同一次改动中更新对应知识库文档及相关导航，保持知识库与当前项目实现一致。新增或删除功能时也应同步补充或移除相关说明，不能只修改代码而保留过时文档。
- `docs/knowledge-base/` 维护功能机制、源码调用链、生命周期、设计取舍和验证边界；总导航由该目录 README.md 维护。
- `nativelib/CONFIGURATION.md` 是配置字段、默认值与校验限制的详细参考；`nativelib/FPS_ALGORITHM.md` 是 FPS 公式与统计口径的详细参考。知识库引用这些文档，避免重复维护完整参数表。
- 修改初始化或公共 API 时同步知识库 02 和配置指南；修改 Crash/Jank/FPS 时同步 03/04/05；修改 Looper 或线程/JNI 时同步 06/07。
- 修改网络、队列、响应处理或数据模型时同步 08/09；服务端契约应核对 apm-server 的对应文档，客户端与服务端差异必须明确记录。
- 修改示例、构建、依赖或测试入口时同步 10/11；能力状态集中更新 00，待处理差异和验证缺口集中更新 12。
- 文档中的“已实现”“存在测试”“测试通过”“真机通过”“联调通过”分别需要对应证据。未运行的验证标注为未执行，不沿用历史成功记录替代当前结果。
- 新增专题必须加入总导航，使用仓库相对源码链接和函数名定位；跨仓库链接标明外部检出依赖。保留已有文档路径，必要迁移时同步所有引用。
- 文档修改完成后检查本地链接、导航覆盖、代码/配置一致性并运行 `git diff --check`。纯文档修改不要求运行 Android 构建；涉及实现时按原测试指南验证。
- 文档示例使用占位凭据，不复制实际 App Key、设备隐私数据或机器专属运行产物。
