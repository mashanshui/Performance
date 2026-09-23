## Why

`nativelib` 已同时承载 SDK 装配、Crash、Jank、FPS、内存指标、内存泄漏、网络传输与原生工具，公共设施散落在具体功能包，Rhea、KOOM 和自有 CMake 构建也绑定在同一模块。项目仍处于初次开发阶段，现在建立单向依赖和按需接入能力，可以减少后续功能扩展的耦合与消费者的非必要依赖。

## What Changes

- 按阶段迁移为八个生产模块：`performance-core`、`performance-transport`、`performance-crash`、`performance-jank`、`performance-metrics`、`performance-leak`、`performance-native-tools`、`performance-sdk`。
- 提取身份、元数据、进程判断、网络类型判断、原子写文件与生命周期契约；共享传输层只提供 HTTP 基础设施，各功能拥有自己的 API、DTO、队列和上传语义。
- 将 Rhea、KOOM、自有 JNI 的实现和构建依赖分别限制在 Jank、Leak、Native 工具边界；FPS、内存指标和纯 Kotlin/Android Looper 工具归入 Metrics。
- 提供全量聚合入口和显式按需装配入口。仅选择 Crash 或 Metrics 的消费者无需依赖 Rhea、KOOM、ShadowHook 或自有 native 产物；功能开关与依赖选择分别定义。
- 保留统一运行身份、默认功能配置、各链路协议、失败回滚及资源所有权语义；公共入口不再暴露 Rhea/KOOM 类型，业务操作通过 SDK 自有能力句柄提供。
- **BREAKING**：最终删除旧 `:nativelib` Gradle 生产模块与旧单体发布入口；调整接入坐标、配置归属和公共 API，不提供旧接口转发层。旧目录文档迁入知识库，并更新内容和源码链接。
- 明确分发责任：全量 SDK 聚合监控能力，Native 工具由宿主额外显式依赖；库产物不再声明应用级明文流量策略，示例保持自身显式配置。
- 迁移对应单元测试、仪器测试、consumer rules 和发布配置，新增精简接入验证工程及依赖边界检查，同步示例和知识库。

## Capabilities

### New Capabilities

- `modular-sdk-composition`：显式选择功能、全量装配、依赖隔离、共享身份与生命周期、第三方类型封装和既有采集上传行为约束。
- `modular-sdk-distribution`：模块化 Maven 发布、依赖元数据、consumer rules、Manifest 与原生库边界，以及独立消费者的可验证接入。

### Modified Capabilities

无。当前仓库尚无已有主规格；本次为新增按需接入和模块化分发能力建立验收规范，不将所有历史功能重新建模。

## Impact

- 代码与构建：`settings.gradle.kts`、根构建配置、`nativelib/` 生产和测试代码、`app/` 示例及测试、新模块和精简验证工程。
- API：`PerformanceSdk`、`PerformanceConfig`、`PerformanceComponentFactory`、`NativeServiceConfig` 和 `NetworkClientFactory` 的职责重新划分；跨模块仅公开必要契约，Reporter/Store/Uploader 继续保留实现封装。
- 依赖与分发：重新分配 Rhea、KOOM、ShadowHook、OkHttp/Retrofit、协程等依赖；所有库模块统一版本发布到 Maven Local，本次不发布远程仓库。
- 文档：知识库导航及 00—14 中受影响的架构、初始化、链路、测试与能力状态；将配置与 FPS 算法说明迁入 `docs/knowledge-base/16-SDK配置指南.md`、`docs/knowledge-base/17-FPS算法说明.md`，旧 README 内容合并到 15。
- 服务端：不修改 `apm-server`，不变更认证头、端点、JSON/ZIP/multipart 协议或事件身份语义。
- 边界：本提案仅生成规划文件，不代表已经完成拆分、构建、真机验证或服务端联调。实施按阶段推进，但八个生产模块及精简接入验证均属于本变更的完成范围。
