# SDK 模块化拆分说明

2026-09-22 起，`nativelib` 不再是 Gradle 生产模块。原单体实现按职责拆为八个 Android Library。旧目录已删除，配置指南和 FPS 算法说明迁入本知识库。

## 模块边界

| 模块 | 职责 | 主要依赖 |
| --- | --- | --- |
| `performance-core` | 应用元数据、运行身份、匿名设备 ID、共享事件上下文、原子文件和组件生命周期 | Android、Kotlin |
| `performance-transport` | OkHttp/Retrofit 会话、App-Key 认证、超时和通用网络结果 | `performance-core` |
| `performance-crash` | Crash 配置、事件映射、文件队列和上传 | Core、Transport |
| `performance-jank` | Rhea 适配、卡顿事件、ZIP 队列和导出 | Core、Transport、Rhea |
| `performance-metrics` | FPS、PSS/VSS/Java heap、Looper 监控及指标队列 | Core、Transport |
| `performance-leak` | Activity 泄漏检测、KOOM 准备/dump、report 队列和上传 | Core、Transport、KOOM |
| `performance-native-tools` | NativeLib、线程/CPU/GC/Hook 工具、CMake 和 `arm64-v8a` JNI | Core、ShadowHook |
| `performance-sdk` | 全量配置、默认初始化、组件编排、共享会话和公开句柄 | 上述业务模块 |

依赖方向保持单向：Core 不依赖业务模块，Transport 不包含业务 DTO/API，Crash、Jank、Metrics、Leak 和 Native Tools 之间不互相依赖，SDK 只负责组合。SDK 初始化时先创建共享身份和 Transport 会话，再按组件顺序执行准备与启动；关闭时按逆序释放功能并最后关闭 Transport。外部传入的 OkHttp/Retrofit 资源由调用方拥有，SDK 不会误关。

## 接入方式

接入项目建议使用 Kotlin 1.9.25，并检查实际解析的传递依赖未重新引入 Kotlin 2.x。
本地发布更新、构建兼容范围和独立消费者验收见
[Kotlin 降级记录](11-构建测试与本地发布.md#2026-10-04-kotlin-兼容基线降级)。

全量接入使用 `com.example.nativelib:performance-sdk:1.0.0`，示例配置和初始化入口见 [SDK 配置指南](16-SDK配置指南.md) 与 [PerformanceSdk](../../performance-sdk/src/main/java/com/shanshui/performance/PerformanceSdk.kt)。需要精简依赖时可直接使用 `performance-crash`、`performance-metrics`、`performance-jank` 或 `performance-leak`；网络能力通过 `performance-transport` 传入，原生调用单独依赖 `performance-native-tools`。八个模块统一发布 Release AAR、sources JAR、POM 和 Gradle module metadata，版本由 `performance.version` 统一控制，默认 group 为 `com.example.nativelib`。旧 `nativelib.*` 发布属性和单体坐标已移除。

在应用模块的 Gradle 依赖中加入：

```kotlin
implementation("com.example.nativelib:performance-sdk:1.0.0")
```

然后在 `Application.onCreate()` 中初始化：

```kotlin
val sdk = PerformanceSdk.initialize(
    application = this,
    appKey = BuildConfig.PERFORMANCE_APP_KEY,
    config = PerformanceConfig(),
)
```

在项目根目录执行 `.\gradlew.bat publishToMavenLocal -Pperformance.version=1.0.0 --no-daemon` 可将全部模块发布到当前用户的 Maven Local；构建与独立消费者验收见[构建测试与本地发布](11-构建测试与本地发布.md)。

## 迁移边界

旧 `PerformanceConfig`、`NativeServiceConfig`、全能 `PerformanceComponentFactory` 和旧 `:nativelib` 坐标不再提供兼容转发。请将配置改为 `performance-sdk` 的 `PerformanceConfig`，FPS 配置位于顶层 `fps` 字段；仅接入某项能力时使用对应模块的公开组件和配置。`performance-native-tools` 继续生成 `arm64-v8a` 的 `libnativelib.so`，保留 `NativeLib` 的 JNI 类名、方法名和 `System.loadLibrary("nativelib")` 约定；各 AAR 的 consumer rules 由最终宿主 R8 合并。

## 当前验证范围

已完成主机侧 Core、Transport、Metrics、Crash、Leak、Jank 单元测试，SDK 配置与聚合层集成测试、Native Tools 的 `arm64-v8a` Release AAR 构建、示例 App Debug/Release 与 Release 仪器 APK 构建，以及 `verification/modular-consumer` 中仅 Crash、仅 Metrics、全量 SDK 加 Native Tools 三个消费者的 Debug/Release（含 R8）构建。Pixel 4 XL/API 33 上完整 `:app:connectedReleaseAndroidTest` 的 9 项回归全部通过，包含真实 KOOM report JSON 入队、`:heap_analysis` 进程建索引和异步回调；三个独立消费者的 Release 设备烟测完成，Full 烟测显式关闭 Jank/Memory Leak。本机 apm-server 五组 HTTP 契约回归共 25 项通过；Android 客户端到真实服务地址的网络联调仍需单独执行。基线中的旧 `:nativelib` 构建失败（共享 Gradle 锁与本地依赖解析问题）保留在 [baseline.md](../../openspec/changes/archive/2026-09-23-modularize-performance-sdk/baseline.md)，不作为新模块通过证据。
