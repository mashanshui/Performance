# SDK 初始化与生命周期

[返回导航](README.md)

## 入口与目的

`PerformanceSdk` 把 Crash、Jank、FPS、主进程内存指标，以及 Activity 泄漏检测和 KOOM OOM 触发的启动、配置和资源关闭集中管理。应用在 `Application.onCreate()` 中显式调用；本库自身 Manifest 没有自动初始化 Provider。这里的“自动初始化”是指调用 `PerformanceSdk.initialize` 后，SDK 按配置自动创建并注册泄漏检测器和 KOOM，应用不需要直接调用内部初始化任务。

~~~kotlin
// appKey 由应用自己的配置来源提供；完整字段见配置指南。
val sdk = PerformanceSdk.initialize(application, appKey, PerformanceConfig())
~~~

参数清单和默认值统一维护在 [配置指南](../../nativelib/CONFIGURATION.md)。`service` 管网络与公共维度，`crash` 管异常事件，`jank` 管 Rhea 和 ZIP，FPS 子配置位于 `jank.fps`，`memory` 管主进程内存采样和上传，`memoryLeak` 管 Activity 弱引用重检、KOOM 触发和 report 上传；两条内存链路互不复用事件队列。

## 初始化调用链

~~~text
PerformanceSdk.initialize
  → trim App Key，拒绝空白
  → 持有 instance 锁，检查是否已有相同配置实例
  → PerformanceConfig.toNativeServiceConfig
  → ApplicationMetadataResolver.resolve
  → componentFactory.createNetwork
  → 读取/生成共享匿名设备 ID
  → RuntimeIdentityProvider.current：生成本进程共享的 sessionId/processId
  → 按开关 startCrash
  → 按开关 initializeJank：Rhea → Jank Reporter
  → 按 FPS 开关与进程条件 initializeFps
  → 按 memory 开关与主进程条件 initializeMemory
  → 按 memoryLeak 开关、API 21..36 与主进程条件 initializeKoom
  → 创建 MemoryLeakReportReporter，恢复 report 队列并启动上传调度
  → initializeMemoryLeak：注册 Activity 回调并启动 activity-leak-watcher
  → 保存完整 SDK 实例
~~~

匿名设备 ID 由 Crash 目录下的 `anonymous-device-id` 文件保存，即使仅启用 Jank 或 `memoryLeak` 也复用这一实现。应用元数据包含包名、版本名和 versionCode，默认 buildId 为 `versionName-versionCode`。Crash 配置可以覆盖其事件元数据，Jank 另有 buildId 覆盖；FPS 和内存泄漏 report 使用 Application 元数据和公共派生 buildId。

SDK 在本进程第一次初始化时生成并缓存一个 `RuntimeIdentity`。主进程的 `processId` 与本次进程启动的
`sessionId` 相同；子进程各自生成独立的 `sessionId`/`processId`。Crash、FPS、memory_sample、
内存泄漏 report metadata 和 Rhea Jank ZIP 都复用当前进程的 `processId`，事件重试不重新生成；
JankEvent 仍由调用方提供 `sessionId`，SDK 会拒绝与当前 SDK 会话不一致的事件。Activity 泄漏检测本身
不单独生成事件，但其 report metadata 使用同一身份。

## Activity 泄漏检测与 KOOM 初始化

当 `memoryLeak.enabled=true`、当前进程为主进程且 Android API 位于 21..36 时，`PerformanceSdk` 通过 `PerformanceComponentFactory` 创建 `MemoryLeakReportReporter`，再依次调用 `OOMMonitorInitTask.init(application, reporter)` 和 `ActivityLeakWatcher`。KOOM 的 CommonConfig、监控配置和后台循环只在进程内首次完成基础初始化；循环由 SDK 以 5 秒延迟启动。`PerformanceConfig.memoryLeak` 不会改变 PSS/VSS/Java heap 的采样开关或周期。

KOOM 的 `HeapAnalysisService` 在 `:heap_analysis` 独立进程中执行。该进程的宿主
`Application.onCreate` 进入 `PerformanceSdk.initialize` 时，SDK 会先调用
`OOMMonitorInitTask.ensureCommonConfig()`，仅初始化 KOOM CommonConfig，不创建泄漏检测器、
不注册 OOM 配置，也不启动主进程监控循环，避免分析服务初始化 `OOMFileManager` 时访问未初始化
的 `MonitorManager.commonConfig`。

`ActivityLeakWatcher` 注册 `Application.ActivityLifecycleCallbacks`，但生命周期回调只做轻量状态更新：

1. `onActivityDestroyed` 为 Activity 创建 `WeakReference` 候选，按 `gcDelayMillis` 延迟第一次检查；同一个仍存活对象不会重复入队。
2. 到期任务在专用 `activity-leak-watcher` HandlerThread 上执行 `Runtime.getRuntime().gc()`，等待约 100ms 后执行 `runFinalization()`，然后检查弱引用；`gcDelayMillis` 只控制销毁后的首次扫描延迟。
3. 弱引用已清除的候选直接移除；仍存活的候选增加重检次数，并按当前前台/后台周期继续调度。
4. 重检次数达到 `maxRecheckCount` 时移除候选并触发一次 `OOMMonitorInitTask.dump()`，最终调用 KOOM 的 `OOMMonitor.dumpAndAnalysis()`；候选在回调前已移除，避免同一候选重复触发。

`skipWhenDebuggerConnected=true` 时，到期扫描只推迟候选，不执行 GC、重检或 dump 回调。检测器内部只保存弱引用，不因候选队列延长 Activity 生命周期。KOOM `OOMMonitor.dumpAndAnalysis()` 在本 SDK 的泄漏路径由 `activity-leak-watcher` 后台线程调用；KOOM 后续分析任务使用其后台分析服务。分析成功后，报告 uploader 将 metadata/report 原子写入独立队列并由 `memory-leak-report-upload` 线程发送 multipart 请求；不上传、不解析 HPROF。

KOOM 自身维护进程级 `mHasDumped`：第一次 `dumpAndAnalysis()` 在进程中设置后，后续监控循环和手动触发都会被跳过，最多产生一次 HPROF dump/analysis 尝试。`OOMMonitorInitTask.stop()`、SDK `close()` 和初始化回滚会停止循环、关闭 report 上传器，但不重置这个标记；同一进程关闭后重新初始化也不能再次触发 KOOM dump。report 队列中未收到 `accepted`/`duplicate` 的任务会保留到下一次初始化恢复。

## 幂等、降级与异常

- 相同 trim 后 App Key 指纹、相等的 `PerformanceConfig` 返回同一实例。配置比较采用数据类相等，不保证“字符串规范化后相同”的不同配置对象也被接受。
- 不同配置或 Key 重复初始化抛出 `IllegalStateException`，不会热更新现有实例。
- 空白 Key 直接抛出 `IllegalArgumentException`。公开配置构造时也可能先抛出 `VALIDATION` 阶段异常。
- 组件失败通过 `PerformanceInitializationException.stage` 表达；枚举包含 `VALIDATION/METADATA/NETWORK/CRASH/JANK/FPS/MEMORY/MEMORY_LEAK`。
- Rhea 的 `UNSUPPORTED_DEVICE`、`NOT_MAIN_PROCESS` 为预期降级；已启用的 Crash 保持运行。
- `isJankAvailable` 代表 Jank Reporter 就绪；`isFpsAvailable` 代表 FPS Reporter 的开关与进程条件，不代表某个 Window 已开始产生有效帧。
- `isMemoryAvailable` 代表主进程内存 Reporter 已创建；内存采样不依赖 Jank/FPS 开关，子进程不会创建 Reporter。
- `isMemoryLeakAvailable` 代表 Activity 泄漏检测器和 KOOM 联动已创建；API 不在 21..36 或当前为子进程时返回 `false`，不会创建这条链路。
- FPS 不以 `isJankAvailable` 为直接开关：Rhea 平台降级时仍独立检查 FPS 条件；关闭 `jank.enabled` 则同时关闭 FPS。

初始化失败会尝试清理已创建资源；未包装的组件异常阶段由 SDK 根据组件状态推断，不应把阶段字段理解为对底层根因的完整分类。泄漏链路在 KOOM 已启动而 watcher 创建失败时也会停止本 SDK 启动的 KOOM 循环。

## 运行时操作

| API | 当前语义 |
| --- | --- |
| `current()` | 返回本进程当前实例，未初始化为 null |
| `flush()/flushAsync()` | 请求后台刷新；不等待上传完成，不跳过重试时间 |
| `pendingCrashEventCount()` | Crash events 队列项数 |
| `pendingJankArtifactCount()` | Jank 元数据队列项数，可能包含待本地删除项 |
| `pendingFpsEventCount()` | FPS 封存事件数，不包含 current.json 和 Helper 当前桶 |
| `isMemoryAvailable` / `pendingMemoryEventCount()` | 内存 Reporter 是否可用、内存封存事件数 |
| `isMemoryLeakAvailable` | Activity 泄漏检测器、KOOM 和 report 上传器是否已创建；当前没有 pending report 数量 API |
| `sessionId` | 当前进程本次应用启动实例的只读 UUID v4；供 JankEvent 等调用方复用 |
| `processId` | 当前 Android 进程的只读 UUID v4；主进程等于 `sessionId`，子进程独立 |
| `setFpsScene(activity, scene)` | 设置场景，null 恢复 Activity 类名；非主线程调用会转发 |
| `exportAndEnqueue(event, callback)` | 请求 Rhea 导出并入队；回调的 queued 不代表服务端确认 |
| `close()` | 解绑、关闭、封存；之后可重新初始化 |

## 关闭与取舍

`close` 使用原子标志保证重复调用无副作用，与初始化共用锁。实际释放顺序先关闭 Activity 泄漏 watcher，再停止本 SDK 启动的 KOOM 循环和 report 上传器，然后关闭内存 Reporter、FPS、Jank、Crash、本 SDK 启动的 Rhea 和共享网络。KOOM 的 `mHasDumped` 不会被重置。

初始化失败的 `rollback()` 使用同样的泄漏 watcher → KOOM → 内存 → FPS → Jank → Crash → Rhea → 网络顺序，尽量释放已经创建的资源。FPS 关闭时把磁盘工作提交给专用线程，但会通过 `task.get()` 等待，因此调用线程仍可能等待持久化完成。report 和内存事件每次产生即原子落盘，关闭只停止生命周期回调和调度，不删除未确认任务。关闭不承诺上传完成；下次初始化恢复队列。Crash close 恢复旧异常 handler 的前提是当前 handler 仍是自己安装的实例。

## 源码与验证依据

- [PerformanceSdk.initialize/rollback/close](../../nativelib/src/main/java/com/shanshui/performance/PerformanceSdk.kt)
- [组件工厂](../../nativelib/src/main/java/com/shanshui/performance/PerformanceComponentFactory.kt)
- [RuntimeIdentity](../../nativelib/src/main/java/com/shanshui/performance/identity/RuntimeIdentity.kt)
- [ActivityLeakWatcher](../../nativelib/src/main/java/com/shanshui/performance/memory/leak/ActivityLeakWatcher.kt)
- [MemoryLeakReportReporter](../../nativelib/src/main/java/com/shanshui/performance/memory/leak/MemoryLeakReportReporter.kt)
- [OOMMonitorInitTask](../../nativelib/src/main/java/com/shanshui/performance/memory/oom/OOMMonitorInitTask.kt)
- [配置映射](../../nativelib/src/main/java/com/shanshui/performance/config/NativeServiceConfig.kt)
- [ApplicationMetadataTest](../../nativelib/src/test/java/com/shanshui/performance/ApplicationMetadataTest.kt)、[PerformanceConfigTest](../../nativelib/src/test/java/com/shanshui/performance/PerformanceConfigTest.kt)、[NativeServiceConfigTest](../../nativelib/src/test/java/com/shanshui/performance/config/NativeServiceConfigTest.kt)、[RuntimeIdentityTest](../../nativelib/src/test/java/com/shanshui/performance/identity/RuntimeIdentityTest.kt)

以上测试覆盖纯值和配置边界。本轮约定的 Wrapper 命令因 `E:\AndroidSDK\.gradle\wrapper\dists\gradle-8.13-bin\ap7pdhvhnjtc6mxtzz89gkh0c\gradle-8.13-bin.zip.lck` 拒绝访问而无法启动；使用同一 Gradle 8.13 发行版的直接 `gradle.bat` 执行 `:nativelib:testDebugUnitTest :nativelib:assembleDebug :app:assembleDebug --console plain` 已 `BUILD SUCCESSFUL`。`:nativelib:assembleDebugAndroidTest` 已通过，`:nativelib:connectedDebugAndroidTest` 在 Pixel 4 XL/Android 13 上 6/6 通过；2026-09-12 又在 Huawei EML-AL00/Android API 29 以示例快速配置验证了真实 KOOM dump、`:heap_analysis` 分析进程初始化、Activity 泄漏路径和 JSON 产物。默认 10 次重检、服务端联调和更广设备/API 兼容性仍未验证。
