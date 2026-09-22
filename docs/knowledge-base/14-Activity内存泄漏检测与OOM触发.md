# Activity 内存泄漏检测与 OOM 触发

[返回导航](README.md)

## 状态与范围

本文描述 `memoryLeak` 配置、Activity 销毁后的弱引用重检，以及确认疑似泄漏后与 KOOM 的
dump 联动和 report JSON 持久上传。本功能不解析 HPROF；HTTP 请求只发送服务端要求的
metadata 与 report，原始 HPROF 只在本地回调中清理。

## 配置默认值与限制

`PerformanceConfig.memoryLeak` 默认开启，默认值和校验来自 `MemoryLeakConfig`：

| 字段 | 默认值 | 校验与含义 |
| --- | --- | --- |
| `enabled` | `true` | 是否启用 Activity 泄漏检测与 KOOM 联动 |
| `foregroundScanIntervalMillis` | `60000` | 前台扫描周期，范围 `1ms..7 天` |
| `backgroundScanIntervalMillis` | `1200000` | 后台扫描周期，范围 `1ms..7 天` |
| `maxRecheckCount` | `10` | 单个 Activity 最大重检次数，范围 `1..100` |
| `gcDelayMillis` | `2000` | Activity 销毁后到首次 GC/检查的延迟，范围 `1ms..5 分钟` |
| `skipWhenDebuggerConnected` | `true` | 调试器连接时只推迟到期候选 |

`gcDelayMillis` 从 `onActivityDestroyed` 入队时开始计时，表示销毁后的首次 GC/检查延迟，
不是 GC 后再等待 2 秒。扫描在 `activity-leak-watcher` 的 `HandlerThread` 上执行 GC，随后
等待约 100ms 再执行 finalization；这个约 100ms 的内部间隔不要与销毁后的 2 秒延迟混淆。

## 初始化与调用链

SDK 只在主进程且 Android API 21..36 时创建这条链路；`memoryLeak.enabled=false`、子进程或
不支持的 API 范围都会跳过，不影响其他组件的初始化。完整调用链为：

```text
PerformanceSdk.initialize
  → PerformanceComponentFactory.isMemoryLeakAvailable
  → PerformanceComponentFactory.initializeKoom
  → MemoryLeakReportReporter：恢复队列并启动上传调度
  → OOMMonitorInitTask.init
  → PerformanceComponentFactory.initializeMemoryLeak
  → ActivityLeakWatcher
  → OOMMonitorInitTask.dump
  → OOMMonitor.dumpAndAnalysis()
```

`OOMMonitorInitTask.init` 负责 KOOM CommonConfig、监控配置和 OOM 循环初始化；本 SDK 启动的
循环延迟 5 秒开始。`OOMReportUploader.upload` 回调把 report 和 metadata 原子写入
`noBackupFilesDir/performance-memory-leak-reporter` 后触发后台上传；回调不打印完整 report，
也不在 KOOM 线程执行网络 IO。

KOOM 的 `HeapAnalysisService` 运行在独立的 `:heap_analysis` 进程。该进程也会执行宿主的
`Application.onCreate`，SDK 入口会先调用 `OOMMonitorInitTask.ensureCommonConfig()`，只补齐
KOOM 的 `CommonConfig`，不注册 OOM 监控配置、不启动循环。这样 `OOMFileManager` 在分析服务中
读取 `MonitorBuildConfig.VERSION_NAME` 时不会访问未初始化的 `MonitorManager.commonConfig`。

## Activity 生命周期与重检

`ActivityLeakWatcher` 注册 `Application.ActivityLifecycleCallbacks`。生命周期回调只做轻量的
前后台状态维护和候选入队：

1. `onActivityDestroyed` 为销毁的 Activity 创建 `WeakReference` 候选；同一仍存活对象不会重复入队。
2. 到期任务在专用 `HandlerThread` 上执行 GC、约 100ms 等待和 finalization，然后读取弱引用。
3. 弱引用已清除的候选移除；仍存活的候选增加重检次数，并按当前前台/后台扫描周期继续调度。
4. 达到 `maxRecheckCount` 时先移除候选，再调用泄漏回调，避免同一候选重复触发 KOOM。

`skipWhenDebuggerConnected=true` 时，到期扫描只推迟候选，不执行 GC、重检或 dump。检测器只保存
弱引用，不会因候选队列延长 Activity 生命周期。

## 关闭、回滚与 KOOM 限制

`ActivityLeakWatcher.close()` 注销 Activity 生命周期回调，清空弱引用候选，取消待执行任务，
停止并等待其 `HandlerThread`。`PerformanceSdk.close()` 或初始化回滚随后停止本 SDK 启动的
KOOM 循环；这些操作不会重置 KOOM 的进程级 dump 状态。

KOOM 的 `mHasDumped` 保证同一进程最多执行一次 HPROF dump/analysis 尝试。第一次
`OOMMonitor.dumpAndAnalysis()` 设置该标记后，后续监控循环、泄漏回调或手动触发都会被跳过；
关闭后在同一进程重新初始化也不能再次 dump。SDK 关闭时停止上传调度器，但不会删除尚未
收到服务端 `accepted`/`duplicate` 的 report 队列项。

## Report 上传

服务端接收接口为 `POST /ingest/v1/memory-reports`。每个任务包含两个 JSON multipart part：
`metadata` 和 `report`，metadata 使用 SDK 进程级 `sessionId`/UUID v4 `processId`，App Key 由公共
网络拦截器写入 `X-App-Key`；请求中不包含 `hprof`。
`eventId` 在首次入队时生成，网络重试沿用同一个 ID 和身份字段。HTTP 200 且响应状态为 `accepted` 或
`duplicate` 才确认删除队列项；网络异常、超时和 503 保留原内容重试，400、401、403、409、
413、415 进入 dead-letter。

报告上限为 2 MiB，队列配额为 20 MiB，任务保留 7 天，最多重试 10 次。report 入队成功后
删除 KOOM 原始 JSON；HPROF uploader 不上传文件，只按 KOOM 回调约定删除本地 HPROF。
字段和服务端校验以外部检出仓库 `F:/IdeaProjects/apm-server/docs/api/memory-leak-reports-api.md`
为准。

## 验证边界

`:nativelib:testDebugUnitTest` 本轮通过，覆盖 multipart 请求、accepted/duplicate、队列恢复、
重试和永久错误隔离。`:nativelib:connectedDebugAndroidTest` 在 Pixel 4 XL / Android 13 上
6/6 通过；该测试使用 fake callback 验证真实 Activity 生命周期、后台回调和 close 清理，
未执行真实 KOOM dump。

2026-09-12 已在 Huawei EML-AL00 / Android API 29 上使用示例快速配置完成真实设备烟测：泄漏确认、
KOOM HPROF dump、`:heap_analysis` 进程建索引、Activity 静态引用路径分析和 JSON 输出均有 Logcat
证据，且未再出现 `commonConfig` 未初始化异常。本轮未等待默认配置的第 10 次重检，也未验证真实
服务端接收结果，因此不能表述为默认时序、长期设备兼容或完整服务端联调已通过。

源码入口：[PerformanceSdk](../../nativelib/src/main/java/com/shanshui/performance/PerformanceSdk.kt)、
[PerformanceComponentFactory](../../nativelib/src/main/java/com/shanshui/performance/PerformanceComponentFactory.kt)、
[ActivityLeakWatcher](../../nativelib/src/main/java/com/shanshui/performance/memory/leak/ActivityLeakWatcher.kt)、
[MemoryLeakReportReporter](../../nativelib/src/main/java/com/shanshui/performance/memory/leak/MemoryLeakReportReporter.kt)、
[MemoryLeakReportStore](../../nativelib/src/main/java/com/shanshui/performance/memory/leak/MemoryLeakReportStore.kt)、
[OOMMonitorInitTask](../../nativelib/src/main/java/com/shanshui/performance/memory/oom/OOMMonitorInitTask.kt)、
[MemoryLeakConfig](../../nativelib/src/main/java/com/shanshui/performance/PerformanceConfig.kt)。
