# 模块拆分实施基线

记录时间：2026-09-22

## 工作区边界

- `git status --short` 仅发现用户/当前任务留下的未跟踪目录 `.agents/` 与 `openspec/`；本次实施不覆盖或回退这两个目录中的既有内容。
- 实施前生产模块为 `:app` 与 `:nativelib`，根 `settings.gradle.kts` 只声明这两个模块。
- `nativelib` 同时包含 SDK 装配、Crash、Jank、FPS、内存指标、Activity 泄漏、Looper、网络、线程和 JNI/CMake；测试位于同一模块的 `src/test` 与 `src/androidTest`。

## 迁移清单

| 目标模块 | 迁移范围 | 测试归属 |
| --- | --- | --- |
| `performance-core` | 身份、元数据、匿名设备 ID、进程/网络类型判断、原子文件写入、共享上下文与组件契约 | `ApplicationMetadataTest`、`RuntimeIdentityTest` 及新增契约测试 |
| `performance-transport` | NetworkConfig、NetworkResult、共享 OkHttp/Retrofit 会话、认证/超时/日志 | `NetworkClientTest` 中的基础传输测试及新增所有权测试 |
| `performance-crash` | `crash/` 业务实现、Crash DTO/API/客户端、队列、上传配置 | Crash 目录下 JVM/仪器测试 |
| `performance-jank` | `jank/` 实现、Rhea 适配、ZIP 队列、业务 API/DTO/客户端 | Jank 目录下 JVM 测试及新增映射测试 |
| `performance-metrics` | FPS、非 Leak/OOM 内存指标、MemoryUtils、Looper 与消息工具 | FPS、Memory、Looper 相关 JVM/仪器测试 |
| `performance-leak` | Activity 泄漏、KOOM/OOM 准备、report 队列/上传/API | `memory/leak` 及相关仪器测试 |
| `performance-native-tools` | NativeLib、CMake、Hook、GC、CPU、线程工具、JNI keep | Native/JNI 相关验证 |
| `performance-sdk` | PerformanceSdk、PerformanceConfig、默认装配与公开能力句柄 | 聚合入口集成测试 |
| `app` | 示例应用改为发布坐标接入 SDK 与 Native 工具，保留自身明文策略 | App JVM/仪器测试 |

实施时记录的旧文档路径为 `nativelib/README.md`、`nativelib/CONFIGURATION.md`、`nativelib/FPS_ALGORITHM.md`；后续目录清理将内容分别归入 `docs/knowledge-base/15-SDK模块化拆分说明.md`、`docs/knowledge-base/16-SDK配置指南.md` 和 `docs/knowledge-base/17-FPS算法说明.md`。

## 构建与发布基线

- 现有 `nativelib` 使用 AGP 8.13.2、Kotlin 2.2.10、compileSdk 36、minSdk 21、Java/Kotlin 11，启用 CMake 3.22.1、Prefab 与 `arm64-v8a`。
- 现有依赖包含 Rhea 1.0.3、KOOM 2.2.3、ShadowHook 2.0.0、Retrofit、OkHttp、协程；旧模块发布坐标默认是 `com.example.nativelib:nativelib:1.0.0`。
- 旧 `nativelib` Manifest 声明 `android:usesCleartextTraffic="true"`；示例 App Manifest 也显式声明该策略。
- 已存在的构建产物中可见 `libnativelib.so`、`libshadowhook.so` 与 `nativelib-debug/release.aar`；这些仅作为实施前快照，不能替代新模块发布验证。

## 实施前命令结果

命令：

```text
.\\gradlew.bat :nativelib:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon --console=plain
```

结果：未进入 Gradle 配置阶段，Wrapper 无法打开共享缓存锁：
`E:\AndroidSDK\\.gradle\\wrapper\\dists\\gradle-8.13-bin\\...\\gradle-8.13-bin.zip.lck`，系统返回“拒绝访问”。

使用工作区 Gradle 用户目录重试后，Wrapper 成功启动，但 `:nativelib:compileDebugKotlin` 因无法解析 `com.kuaishou.koom:koom-java-leak:2.2.3` 与 `io.github.mashanshui:rhea-inhouse:1.0.3` 失败；因此四个基线任务均不得标记为通过。构建同时报告 Android SDK `analytics.settings` 无写权限和既有 Gradle DSL 弃用警告。

## 当前验证边界

- 本文件记录的是实施前快照与失败原因；未将已有 `build/` 产物视为本轮成功证据。
- 依赖可解析后仍需重新运行 JVM、APK、发布、独立消费者和设备任务；设备/服务端证据不得由主机基线替代。
