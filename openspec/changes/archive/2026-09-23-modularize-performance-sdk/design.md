## Context

动机和范围见 [proposal.md](proposal.md)。本设计依据当前工作区源码，属于待实施方案。

实施前只有 `app` 和 `nativelib` 两个 Gradle 模块；当前实现已按下表方向完成拆分。需要拆开的关键耦合如下：

| 当前位置 | 已核对的问题 | 处理方向 |
| --- | --- | --- |
| [PerformanceSdk](../../../../performance-sdk/src/main/java/com/shanshui/performance/PerformanceSdk.kt) | 直接引用 Rhea 类型、判断 KOOM 分析进程、编排所有 Reporter | 通用运行时下沉，入口仅负责默认装配 |
| [PerformanceComponentFactory](../../../../performance-core/src/main/java/com/shanshui/performance/core/PerformanceComponent.kt) | 从 CrashReporter 获取设备 ID，从 MemoryReporter 借用进程判断 | 公共工具移入 Core，各功能公开小型装配入口 |
| [FileCrashQueue](../../../../performance-crash/src/main/java/com/shanshui/performance/crash/FileCrashQueue.kt) | 定义的 AtomicFileWriter 被 Jank、FPS、Memory、Leak 复用 | 原子文件操作移入 Core，队列保留在功能模块 |
| [FpsReporter](../../../../performance-metrics/src/main/java/com/shanshui/performance/fps/FpsReporter.kt) 与 [MemoryReporter](../../../../performance-metrics/src/main/java/com/shanshui/performance/memory/MemoryReporter.kt) | 依赖 Crash 包中的 AndroidNetworkTypeProvider | 网络类型判断移入 Core |
| [NetworkClientFactory](../../../../performance-transport/src/main/java/com/shanshui/performance/network/TransportSession.kt) | 集中持有并创建所有业务客户端 | Transport 仅持有共享 HTTP 设施 |
| [NativeServiceConfig](../../../../performance-sdk/src/main/java/com/shanshui/performance/PerformanceConfig.kt) | 汇集各功能开关、间隔、配额并引用总配置 | 拆成共享配置和各功能配置 |
| [构建脚本](../../../../performance-sdk/build.gradle.kts) | 同时声明 Rhea、KOOM、ShadowHook、CMake 和 ABI | 三类原生依赖分归功能所有者 |

当前 Rhea 已由 `implementation` 引入，示例 App 显式声明 Rhea 依赖；知识库 01 中仍有旧 `api` 表述。实施时以源码为基准更新文档，不能把旧表述当成现状。当前示例 `testBuildType` 为 `release`，且 Release 开启混淆；仪器验证应使用实际生成的 Release 测试任务。

## Goals / Non-Goals

**目标：**

- 用编译期依赖保证功能可选择，而不是依赖运行时开关或 R8 恰好删除未用代码。
- 所有接入方式复用相同的身份生成、初始化、回滚和关闭逻辑。
- 单个功能的队列、网络协议及第三方适配能够在所属模块内维护和测试。
- 明确每个现有源码区域、测试、原生规则与文档的去向，避免只迁移主链路而遗漏工具。

**非目标：**

- 不改变采样算法、默认采集间隔、上传成功判定、重试与配额策略，不新增 HPROF 上传或服务端能力。
- 不建立动态插件发现、反射扫描、独立版本矩阵或通用任务调度平台。
- 不承诺构建时间或 APK 大小的定量改善，不扩展现有原生 ABI/设备支持范围。
- 不拆成每个 Reporter、Uploader、Queue 一个 Gradle 模块，不为旧 API 保留转发兼容层。

## Decisions

### 1. 八个生产模块与单向依赖

| 模块 | 迁移内容与公开边界 | 允许的项目直接依赖 |
| --- | --- | --- |
| `performance-core` | RuntimeIdentity、ApplicationMetadata、设备 ID、进程和网络类型判断、AtomicFileWriter；共享事件上下文、组件契约和通用运行时 | 无 |
| `performance-transport` | NetworkConfig、NetworkResult、共享 OkHttp/Retrofit 创建、认证、超时和关闭 | Core |
| `performance-crash` | `crash/` 中剩余功能实现；Crash 配置、API、DTO、客户端、队列和上传；公开 Crash 组件入口和操作句柄 | Core、Transport |
| `performance-jank` | `jank/`、Jank 配置、Rhea 转换与适配、Jank 网络模型和客户端 | Core、Transport |
| `performance-metrics` | `fps/`、`memory/` 下非 leak/oom 的指标代码和 MemoryUtils；对应网络代码；LooperMonitor、LooperDispatchCore、LooperPrinterHook、MessageCollect、MessageTimeoutMonitor | Core、Transport |
| `performance-leak` | `memory/leak/`、`memory/oom/`、MemoryLeakConfig、report API/DTO/客户端；KOOM 分析进程准备 | Core、Transport |
| `performance-native-tools` | NativeLib、`cpp/`、`hook/`、`gc/`、`cpuusage/`、`thread/`，含线程池与绑核工具 | Core（仅实际需要时） |
| `performance-sdk` | PerformanceSdk、聚合 PerformanceConfig、默认组件装配与可替换装配测试入口 | Core、Transport、Crash、Jank、Metrics、Leak |

SDK 聚合默认监控能力；独立 Native 工具由需要它们的消费者额外引入，示例 App 同时依赖 SDK 和 Native 工具。SDK 初始化不自动开启 Looper 监听、线程池、绑核或 GC 干预，延续现有工具的显式调用及释放责任。

所有生产模块先采用 Android Library，保持当前库的 minSdk、compileSdk 和字节码目标；本次不同时引入纯 JVM 基础模块。纯 Looper 工具不依赖 JNI，归入 Metrics；暂不再增加第九个 Looper 模块。独立线程池连同现有线程工具集中放在 Native 工具模块，未来确有轻量线程工具接入需求时再细拆。

不允许功能模块相互依赖，不允许 Core/Transport 反向依赖功能模块或 SDK，不允许任意新生产模块依赖迁移中的旧 nativelib。以构建脚本声明和解析后的依赖图检查这些约束。

备选方案是只调整包结构：维护成本较低，但无法提供按需依赖隔离；因此不作为目标方案。

### 2. 共享契约与配置拆分

Core 提供 SDK 自有的共享事件上下文，包含运行身份、匿名设备 ID、包版本、buildId、环境和渠道等各功能共同使用的信息。设备 ID 沿用现有持久化位置和算法；身份仍按进程缓存，不因移动源码重新生成。buildId 等共享覆盖值归共享配置，不要求按需 Metrics 接入创建 CrashConfig；全量示例继续传入其编译期 buildId。

Transport 拥有地址、App Key、HTTP 超时和日志配置。Crash/Jank/FPS/Memory/Leak 各自拥有功能参数和校验；SDK 中的总配置仅组合这些公开配置。Rhea 配置转换只存在于 Jank，KOOM 配置转换只存在于 Leak。删除全能 NativeServiceConfig，不把全部功能开关迁入 Core。

跨模块可见性按“配置、组件入口、能力句柄、基础契约”公开；Reporter、Uploader、Store、DTO 等留在所属模块内部。测试迁移到实现所在模块，避免为了旧测试访问而扩大可见性。保留现有包名能降低 JNI 与混淆迁移风险，但包名相同不等于能够跨模块访问 Kotlin `internal`。

### 3. 全量和按需使用同一个通用运行时

Core 的通用运行时接受显式组件列表和 SDK 自有上下文。组件契约区分进程准备、开始采集、异步刷新和关闭；功能组件由其模块公开的工厂创建，可接收 Transport 提供的共享传输会话。Core 不引用 OkHttp、Retrofit、Rhea 或 KOOM。

| 接入方式 | 消费者依赖及行为 |
| --- | --- |
| 全量 | 依赖 `performance-sdk`，继续提供 `PerformanceSdk.initialize(application, appKey)` 的默认初始化形式，由聚合层创建各组件和共享传输资源 |
| 仅 Crash | 依赖 Core、Transport、Crash；创建一次共享上下文/传输会话并注册 Crash 组件，无需 SDK 聚合层 |
| 仅 Metrics | 依赖 Core、Transport、Metrics；选择 FPS/Memory 配置，无需 Crash、Jank、Leak 或 Native 工具 |
| 原生工具 | 额外显式依赖 Native 工具并调用其 API，不隐式创建采集上传运行时 |

组件列表是不可变初始化参数，重复功能 ID 应在启动前拒绝；同一运行时入口相同配置重复初始化返回已有实例，不同配置或不同组件集合报告冲突，不重复注册生命周期监听和线程。全量与按需入口共用这一状态管理，不能各建一套全局单例绕过冲突检查。

运行时记录已成功启动的组件，失败时按逆序关闭；正在启动的组件必须自行释放部分创建的资源。共享传输会话通过通用可关闭资源契约交给运行时管理，组件仅借用，不各自关闭。传入宿主管理的资源时标明外部所有权，运行时不关闭它；默认 SDK 自建资源在所有组件关闭后恰好释放一次。

不使用反射或 ServiceLoader 自动发现组件：显式列表更容易验证缺少功能时不会加载相应类型。

### 4. 特殊进程和第三方状态留在所属功能

Leak 组件在进程准备阶段识别 KOOM 分析进程并初始化必要 CommonConfig；该阶段先于普通采集可用性判断。即使主进程 Activity 检测未启用，已注册 Leak 组件也应完成分析进程所需准备；未依赖或未注册 Leak 的精简消费者不执行相关逻辑。后台 GC、弱引用重检及 dump 调用线程保持当前行为。

Jank 组件负责 Rhea 的可用性和初始化结果映射，区分“不支持”“非主进程”“本次启动”“已有外部实例”等状态；仅停止本次拥有的会话。Leak 同样保留 KOOM 循环和进程级 dump 状态的原有资源归属。

SDK 自有的初始化状态、Jank 事件、导出结果和回调放在对应功能契约中，对外不出现 Rhea/KOOM 类型。Jank 的字段转换以现有调用方实际使用的字段为准进行完整映射，不能在迁移时丢弃事件元数据。第三方回调由 Jank 内部适配后交给消费者；操作通过功能句柄提供，SDK 仅做必要转发。

### 5. 公共网络设施与业务协议分离

Transport 提供共享传输会话及创建服务的必要能力，可以在集成层契约中暴露 Retrofit/OkHttp 类型并据此声明正确的 `api` 依赖；不额外实现一套通用 HTTP 框架。各功能在自己的构造流程中创建 API 和网络客户端，DTO 与业务响应解析不放在 Transport。

仅提取已存在的 AtomicFileWriter 等公共文件原语，不统一各功能队列。保持 Crash/FPS/Memory 批次 JSON、Jank ZIP、Leak report multipart 的请求字段、端点、成功与永久失败判定、恢复和确认删除行为。保持仅上传 report JSON，不上传 HPROF。实施时对照现有契约测试和外部检出的 apm-server 文档；若外部仓库不可用，记录核对缺口，不宣称完成服务端联调。

### 6. 原生构建、Manifest 与混淆按所有者迁移

自有 CMake、Prefab、`arm64-v8a` 和 NativeLib JNI keep 规则仅配置在 Native 工具模块。保留 NativeLib 包类名、native 方法名和 `System.loadLibrary("nativelib")` 对应的 SO 名，Gradle 模块改名不要求 SO 改名。Rhea/KOOM 的传递原生产物仍属于其功能依赖，本次不将它们复制或重新打包进 Native 工具。

共享 SO 重复处理必须检查实际合并产物，不能只复制所有旧 `pickFirst` 就视为完成；精简消费者不携带 SDK 引入的 Rhea、KOOM、ShadowHook 或自有 SO。业务 DTO 和本地队列记录的 consumer rules 随功能迁移，公共反射规则由实际使用方提供，避免依赖 App 的宽泛 keep 兜底。

现有示例 App 已声明明文策略。迁移后生产 AAR 不再通过 `<application android:usesCleartextTraffic="true">` 改写宿主策略，由宿主决定 HTTP 访问；示例保留其显式设置。这是分发边界调整，精简与全量消费者均需验证合并 Manifest。网络权限归实际需要的模块，KOOM 服务只通过 Leak 依赖进入宿主。

### 7. 发布与独立消费者验证

统一发布属性使用 `performance.groupId`、`performance.version`；默认 group 继续采用 `com.example.nativelib`，artifactId 为各模块名，规划默认版本为 `1.0.0`。不继续发布旧 `nativelib` artifact，也不保留旧 `nativelib.*` 属性别名。八个库模块均发布 Release AAR、sources JAR、POM 与 Gradle module metadata，库自身不预混淆，交由宿主 R8 消费模块级规则。

在 `verification/modular-consumer/` 建立独立 Gradle 构建，不使用 `project(...)`、`includeBuild` 或源码替换本次产物；以 Maven Local 的已发布坐标验证。通过三个独立应用模块分别覆盖仅 Crash、仅 Metrics 和全量 SDK 加 Native 工具，避免使用 App 的全量依赖掩盖精简接入缺失。外部 Rhea/KOOM 仍按项目既有仓库解析，不在本变更升级版本。

核心验收矩阵：

| 维度 | 必须提供的证据 |
| --- | --- |
| 模块边界 | 生产依赖无环、无反向/功能互依；精简编译和运行时依赖图均无重型功能 |
| 主机行为 | 已迁移测试通过；补充共享身份、重复初始化、部分失败清理、逆序关闭、共享资源所有权测试 |
| 协议 | 五条链路原有契约及响应分类测试通过，Jank 自有类型映射测试通过 |
| 分发 | 八套发布文件及依赖元数据完整，三个 Maven 消费者编译和 Release 混淆构建通过 |
| 打包 | 检查 APK、Manifest 和 R8 输出；精简不含重型功能的服务或 SO，全量无遗漏/重复加载冲突 |
| 设备 | 支持的 arm64 设备上验证全量和精简初始化、关闭、JNI、Looper、FPS/内存、KOOM 分析进程及 report；记录设备/API/命令 |
| 文档 | 更新全部受影响入口和源码链接，按主机、设备、服务端分别记录证据或待验证项 |

## Risks / Trade-offs

- 公共契约增多 → 仅公开装配需要的类型，测试跟随实现迁移；不把所有 `internal` 改为 `public`。
- Kotlin 可见性和配置迁移引入环 → 先提取共享配置及文件/设备工具，再搬移功能；每阶段检查依赖图。
- KOOM 子进程准备被普通 enabled 判断跳过 → 将准备与采集启动分开，并加入分析进程回归验证。
- JNI 或 R8 规则遗漏 → 保留 JNI 名称，逐模块迁移 consumer rules，以真实 Maven 消费者的混淆 APK 验证。
- 多模块增加 Gradle 配置及发布维护成本 → 统一版本与公共构建约定；首轮合并 FPS、内存、Looper 到 Metrics，暂不继续细分。
- 本地 Maven 旧缓存掩盖发布缺失 → 验收使用本次唯一版本属性并核对元数据，禁止源码依赖替换。
- 历史知识库与当前代码不一致 → 用当前源码和本轮执行结果更新，既有成功记录不能替代本次证据。
- 没有可用设备时无法完成原生验收 → 主机工作继续完成，相关设备任务保持未勾选，不把规划完整或主机通过写成全部完成。

## Migration Plan

1. **基线与公共边界**：记录当前测试、依赖、Manifest、SO 和发布元数据；建立 Core/Transport 和公共契约；迁移公共工具。验收：旧入口仍可编译运行，新基础模块不反向依赖旧模块。
2. **隔离原生能力**：按 Jank、Leak、Native 工具的顺序搬移代码、配置、测试和规则；旧 nativelib 临时作为剩余实现与装配宿主。验收：第三方类型转换归属清晰，原生任务归正确模块，阶段性测试通过。
3. **完成业务与装配**：迁移 Crash、Metrics，建立 SDK 聚合入口和按需入口；更新示例，删除旧模块声明及生产/测试构建入口。旧目录的配置与算法说明迁入知识库，README 内容并入模块化说明，然后删除目录。验收：八模块图满足约束，所有已有能力都有归属，纯 JVM 行为回归通过。
4. **发布、验收与文档**：Maven Local 发布、独立消费者验证、Release 混淆和设备验证、知识库同步。主规格同步和归档不包含在自动实施步骤中，后续按用户明确请求进行。

每阶段保持可审查、可验证；旧模块仅是迁移中的临时宿主，不是最终兼容层。失败时回退本阶段改动到已验证的阶段边界，不回退用户其他修改，不改变服务端数据。精简接入在最终组合完成后统一验收，不能以第一阶段完成替代整个变更完成。
