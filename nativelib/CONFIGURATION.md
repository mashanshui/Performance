# Performance SDK 配置指南

本文介绍 `PerformanceSdk` 的统一初始化方式、默认配置和自定义配置。配置类位于
`com.example.nativelib` 包中，App Key 始终作为初始化参数传入，不放在
`PerformanceConfig` 内。

项目架构和调用链见[知识库导航](../docs/knowledge-base/README.md)。本文记录客户端当前配置；
Crash 的默认 schema v1 与 appId 字段尚未对齐所核对服务端的 v2/packageName 契约，详见
[协议差异](../docs/knowledge-base/09-服务端对接与数据协议.md)。仅修改 schemaVersion 不能解决
事件字段差异。

## 1. 最小初始化

在应用的 `Application.onCreate()` 中传入当前 `Application` 和 App Key：

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        PerformanceSdk.initialize(
            application = this,
            appKey = BuildConfig.PERFORMANCE_APP_KEY,
        )
    }
}
```

不传第三个参数时，Crash、线上卡顿（Jank）和主进程内存指标都会启用，并使用本文的默认值。

### 1.1 从本地 Gradle 属性注入 App Key

示例应用的构建脚本使用 `performance.appKey` 生成 `BuildConfig.PERFORMANCE_APP_KEY`。本地构建时
可以通过 Gradle 属性传入：

```powershell
.\gradlew.bat :app:assembleDebug -Pperformance.appKey="<your-app-key>"
```

当前 App.kt 仍直接传入字符串常量，尚未读取这个 BuildConfig 字段；上面的最小初始化示例
展示接入写法。仅传 Gradle 属性不能改变当前 App.kt 的实际初始化参数。

不要把真实 App Key 提交到源码、版本控制或文档中。SDK 只在运行期将 App Key 放到
请求认证头中，不会将它写入持久队列，也不会在网络日志中输出明文。

## 2. 配置结构

需要覆盖默认值时，使用 `PerformanceSdk.initialize(application, appKey, config)`：

```kotlin
val performance = PerformanceSdk.initialize(
    application = this,
    appKey = BuildConfig.PERFORMANCE_APP_KEY,
    config = PerformanceConfig(
        service = ServiceConfig(
            baseUrl = "https://apm.example.com",
            environment = "release",
            channel = "official",
            connectTimeoutMillis = 3_000L,
            readTimeoutMillis = 3_000L,
            writeTimeoutMillis = 3_000L,
            enableNetworkLogging = false,
        ),
        crash = CrashConfig(
            batchSize = 20,
            maxBatchBytes = 512 * 1024,
            uploadIntervalMillis = 30_000L,
        ),
        jank = JankConfig(
            minSampleIntervalMillis = 10L,
            mappingId = "mapping-release-2026",
            fps = FpsConfig(
                logLevel = FpsLogLevel.SUMMARY,
            ),
        ),
        memory = MemoryConfig(
            foregroundSamplingIntervalMillis = 60_000L,
            backgroundSamplingIntervalMillis = 5L * 60L * 1_000L,
            uploadIntervalMillis = 30_000L,
        ),
    ),
)
```

每个子配置都是可选的；只填写需要调整的字段即可，其余字段仍使用默认值。

## 3. 默认配置

### 3.1 `ServiceConfig`

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `baseUrl` | `http://192.168.0.150:8080` | 服务端根地址，只允许 HTTP/HTTPS，不能带查询参数或片段 |
| `environment` | `debug` | 环境标识，最长 64 个字符 |
| `channel` | `official` | 发布渠道，最长 128 个字符 |
| `schemaVersion` | `1` | Crash 请求协议版本 |
| `connectTimeoutMillis` | `3000` | 连接超时 |
| `readTimeoutMillis` | `3000` | 读取超时 |
| `writeTimeoutMillis` | `3000` | 写入超时 |
| `enableNetworkLogging` | `false` | 是否启用网络日志；启用时 App Key 仍会脱敏 |

### 3.2 `CrashConfig`

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 是否启用 Crash 捕获和上传 |
| `appId` | 从 Application 包名解析 | Crash 事件中的应用标识 |
| `appVersion` | 从 Application 版本名解析 | Crash 事件中的版本名 |
| `versionCode` | 从 Application 解析 | 允许覆盖为非负整数 |
| `buildId` | `versionName-versionCode` | 允许覆盖，最长 256 个字符 |
| `applicationPackage` | 从 Application 包名解析 | 用于判断异常堆栈中的应用帧 |
| `batchSize` | `20` | 每批最多事件数，范围 `1..50` |
| `maxBatchBytes` | `512 KiB` | 单批请求大小，范围 `16 KiB..1 MiB` |
| `uploadIntervalMillis` | `30000` | 周期上传间隔，必须为正数 |

应用元数据默认自动解析。只有需要替换服务端应用标识、版本信息或堆栈包名判断规则时，
才需要覆盖 `appId`、`appVersion` 或 `applicationPackage`。

例如关闭 Crash，或为构建系统提供固定的版本信息：

```kotlin
val config = PerformanceConfig(
    crash = CrashConfig(
        enabled = false,
        appId = "my-product",
        appVersion = "2026.09.0",
        versionCode = 900,
        buildId = "my-product-2026.09.0-900",
        applicationPackage = "com.example.product",
    ),
)
```

### 3.3 `JankConfig`

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 是否启用 Rhea 线上采集 |
| `bufferSizeBytes` | `5 MiB` | 采样缓冲区，范围 `1..16 MiB` |
| `minSampleIntervalMillis` | `10` | 最小采样间隔，不能小于 `5 ms` |
| `diskQuotaBytes` | `20 MiB` | 卡顿产物磁盘额度，必须为正数 |
| `artifactTtlMillis` | `3 天` | 卡顿产物保留时间，必须为正数 |
| `maxArtifactBytes` | `10 MiB` | 单个 ZIP 上限，不能超过磁盘额度，最大 `64 MiB` |
| `foregroundOnly` | `true` | 是否只在前台采集 |
| `enableJniHook` | `false` | 是否启用 JNI Hook |
| `enableObjectAllocation` | `false` | 是否启用对象分配采样 |
| `enableWakeup` | `false` | 是否启用唤醒采样 |
| `enableRusage` | `false` | 是否启用资源使用统计 |
| `enableStackCaptureStats` | `false` | 是否启用堆栈采集统计 |
| `mappingId` | 空字符串 | 传给 Rhea 的符号映射 ID，最长 128 个字符；当前服务端按 buildId 查找 mapping |
| `buildId` | 使用公共派生 buildId | Jank 单独覆盖的构建标识，最长 256 个字符 |
| `uploadIntervalMillis` | `30000` | ZIP 队列周期上传间隔，必须为正数 |

例如降低采样间隔并打开堆栈采集统计：

```kotlin
val config = PerformanceConfig(
    jank = JankConfig(
        minSampleIntervalMillis = 5L,
        enableStackCaptureStats = true,
        mappingId = "mapping-release-2026",
    ),
)
```

`mappingId` 默认为空，不会自动猜测符号映射。该字段会传给 Rhea，但所核对的 apm-server
使用认证应用和 `buildId` 查找 mapping，客户端也不发送 `X-Mapping-Id`；不能把填写 mappingId
等同于服务端已完成映射选择。当前对接方式见[协议对照](../docs/knowledge-base/09-服务端对接与数据协议.md)。

### 3.4 `FpsConfig`

FPS 受 `JankConfig.enabled` 总开关控制。SDK 自动监听页面生命周期，在 API 24 及以上的
主进程硬件加速 Window 中采集 FrameMetrics。静止期间没有 FrameMetrics 回调，不会生成
零帧或低 FPS 记录；同一运行中的相同场景按 `scene + algorithmVersion + refreshRateHz`
合并，服务端接收 `frame_scene_summary` v2。

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 是否启用 FPS 采集和上传 |
| `logLevel` | `OFF` | `OFF`、`SUMMARY` 或 `VERBOSE` |
| `snapshotIntervalMillis` | `5000` | 当前运行快照落盘间隔 |
| `uploadIntervalMillis` | `30000` | 封存 FPS 事件上传间隔 |
| `batchSize` | `20` | 单批最多事件数，范围 `1..50` |
| `maxBatchBytes` | `512 KiB` | 单批 JSON 大小，范围 `16 KiB..1 MiB` |
| `queueDiskQuotaBytes` | `20 MiB` | FPS 封存队列上限，不能小于单批大小 |
| `eventTtlMillis` | `7 天` | FPS 事件保留时间 |

日志等级示例：

```kotlin
val config = PerformanceConfig(
    jank = JankConfig(
        fps = FpsConfig(logLevel = FpsLogLevel.VERBOSE),
    ),
)
```

`SUMMARY` 的摘要字段包括场景、刷新率、有效刷新帧数、活跃渲染时长、原始 FPS、归一化
FPS、平均/最大帧耗时和回调丢失计数。回调丢失计数仅用于诊断，不会被当作 UI 掉帧补入
FPS 计算。`VERBOSE` 会打印原始帧耗时、帧预算、有效耗时及首绘/无效数据/迟到回调的
过滤原因；日志不会输出 App Key、匿名设备标识或完整事件正文。

完整的 `fps-v1` 公式、静止区间处理、刷新率切桶规则和典型日志见
[FPS 算法说明](FPS_ALGORITHM.md)。

### 3.5 `MemoryConfig`

内存指标独立于 Crash、Jank 和 FPS 开关，只在主进程运行。初始化时立即采集一次；Activity
进入前台或确认进入后台时也立即采集一次，随后按对应周期执行。采集和磁盘操作在 SDK 后台线程，
不会放到主线程。事件使用服务端 `memory_sample` v2，PSS 使用 `Debug.getPss()`，VSS 读取
`/proc/self/status` 的 `VmSize`，Java 堆使用 `Runtime.totalMemory() - Runtime.freeMemory()`。

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 是否启用主进程内存采集和上传 |
| `foregroundSamplingIntervalMillis` | `60000` | 前台周期，必须为正数 |
| `backgroundSamplingIntervalMillis` | `300000` | 后台周期，必须为正数 |
| `uploadIntervalMillis` | `30000` | 上传检查周期，必须为正数 |
| `batchSize` | `20` | 单批最多事件数，范围 `1..50` |
| `maxBatchBytes` | `512 KiB` | 单批 JSON 大小，范围 `16 KiB..1 MiB` |
| `queueDiskQuotaBytes` | `20 MiB` | 正常事件和失败隔离文件共用额度 |
| `eventTtlMillis` | `7 天` | 事件和失败隔离文件的保留时间 |
| `maxAttempts` | `10` | 网络或服务端可重试失败的最大次数 |

三个指标可以独立缺失，不能用 0 代替读取失败；真实的零值会保留。单事件 JSON 超过服务端 256 KiB 上限会进入 `dead-letter`。事件生成稳定
`eventId` 后先原子写入 `noBackupFilesDir/performance-memory-reporter/events`，服务端确认
`accepted` 或 `duplicate` 后才删除。网络中断、408、429 和 5xx 会按 `Retry-After` 或退避重试，
永久错误和达到最大次数的事件进入 `dead-letter`。

场景可以在页面运行期间覆盖：

```kotlin
PerformanceSdk.current()?.setFpsScene(this, "checkout")
// 恢复默认的 Activity 完整类名
PerformanceSdk.current()?.setFpsScene(this, null)
```

## 4. 元数据和跨模块共享规则

SDK 初始化时会自动解析并复用以下信息：

- Crash、Jank、FPS 和内存默认从同一 Application 获取元数据，复用公共派生 `buildId`、环境和渠道。
- Crash 可覆盖其事件元数据，Jank 可单独覆盖 `buildId`；FPS 使用 Application 元数据和公共派生 `buildId`。
- 匿名设备 ID 持久化保存，四个 Reporter 复用同一个 ID。
- 四条链路共用网络客户端、超时参数和 App Key 认证信息；内存请求固定发送 `X-Schema-Version: 2`。
- Jank 的 `mappingId` 不参与自动推导，默认保持为空。

sessionId 尚未统一：Crash 和 FPS 各自生成，Jank 由事件构造方传入。公共元数据复用不代表
跨链路会话 ID 一致。事件字段差异以[服务端协议对照](../docs/knowledge-base/09-服务端对接与数据协议.md)为准。

## 5. 初始化结果和异常

### 5.1 返回同一实例

同一进程中，使用相同 trim 后 App Key 和相等的 PerformanceConfig 重复初始化时，SDK 返回原实例。
配置比较使用数据类相等，不是再次规范化后比较：

```kotlin
val first = PerformanceSdk.initialize(this, appKey)
val second = PerformanceSdk.initialize(this, appKey)
check(first === second)
```

不同 App Key 或不同配置重复初始化会抛出 `IllegalStateException`。App Key 比较使用内部
指纹，不会在异常消息中记录原文。调用 `close()` 后可以重新初始化。

### 5.2 失败阶段

配置校验或真正的组件初始化失败时，会抛出
`PerformanceInitializationException`，通过 `stage` 区分阶段：

- `VALIDATION`：配置或限制非法。
- `METADATA`：Application 元数据解析失败。
- `NETWORK`：共享网络客户端创建失败。
- `CRASH`：Crash Reporter 创建失败。
- `JANK`：Rhea 或 Jank Reporter 创建失败。
- `FPS`：FPS 队列或 Activity 生命周期 reporter 创建失败。
- `MEMORY`：内存队列或采样 reporter 创建失败。

初始化失败会回滚已经启动的 Crash handler、Jank Reporter、Rhea 采集和调度器，不留下半初始化
状态。空白 App Key 会立即抛出 `IllegalArgumentException`。

### 5.3 Rhea 不支持设备

当 Rhea 返回 `UNSUPPORTED_DEVICE` 或 `NOT_MAIN_PROCESS` 时，初始化不会整体失败：

- `PerformanceSdk.isJankAvailable` 为 `false`。
- Crash 仍然继续捕获和上传。
- `jankInitResult` 保留实际的 Rhea 状态，便于记录诊断信息。

`INVALID_CONFIG`、`NATIVE_INIT_FAILED`、`MODE_CONFLICT` 等其他状态仍会按 Jank 初始化失败处理。

## 6. 运行时控制

```kotlin
val sdk = PerformanceSdk.current() ?: return

// 查看 Jank 是否可用。
if (sdk.isJankAvailable) {
    sdk.flushAsync()
}

// FPS 仅在 API 24+、主进程和硬件加速窗口可用时采集。
if (sdk.isFpsAvailable) {
    Log.d("Performance", "pendingFps=${sdk.pendingFpsEventCount()}")
    sdk.flushAsync()
}

// 内存指标只在主进程可用时采样；可查看队列并手动触发上传检查。
if (sdk.isMemoryAvailable) {
    Log.d("Performance", "pendingMemory=${sdk.pendingMemoryEventCount()}")
    sdk.flushAsync()
}

// 导出并加入持久 ZIP 上传队列。
val result = sdk.exportAndEnqueue(event) { exportResult ->
    // 回调运行在 Rhea 导出线程；需要更新 UI 时请切回主线程。
    Log.d("Performance", "queued=${exportResult.queued}")
}

// 不再需要 SDK 时关闭；关闭后可以重新初始化。
sdk.close()
```

`flush()` 是 `flushAsync()` 的便捷入口别名，实际上传仍由后台调度器执行，也不跳过队列重试时间。
Crash、Jank 和 FPS 封存队列会在启动、支持的网络恢复回调和周期调度时继续处理；当前没有接入 WorkManager。

FPS 的 flush 只保存 Store 已合并数据的快照并上传封存队列，不主动结算 Helper 当前桶，
也不封存当前会话。页面暂停时只结算到 Store；SDK close 或下次初始化恢复时才封存。
close 在专用后台线程执行 FPS 持久化，但调用线程会等待完成，且不承诺完成网络上传。
详细时序见[FPS 专题](../docs/knowledge-base/05-FPS采集与场景汇总.md)。

## 7. 配置建议

1. 开发环境可以保留默认地址和 `debug` 环境；测试/生产环境应显式覆盖 `baseUrl`、`environment`
   和 `channel`。
2. 先调整 Crash 的批量大小和上传间隔，再根据设备存储空间调整 Jank 磁盘额度。
3. 打开对象分配、JNI Hook 或堆栈统计会增加采集开销，建议仅在确有诊断需求时启用。
4. 启用网络日志只用于排查请求问题，生产环境通常保持关闭。
5. App Key 通过本地构建属性或安全的 CI 注入，不要写入公开仓库。

## Looper 独立配置

`LooperMonitor` 不由 `PerformanceSdk` 自动启动或关闭。使用 `LooperMonitor.sMainMonitor` 或
`LooperMonitor.of(looper)` 获取共享实例，再注册监听；Activity 销毁时只注销自己的监听。

| RecordingConfig 字段 | 默认值 | 含义与限制 |
| --- | --- | --- |
| historyEnabled | false | 保存已完成消息逐条历史 |
| denseEnabled | false | 保存近期消息并累计完成消息数、执行耗时；独立于历史开关 |
| historyCapacity | 200 | 已完成历史容量，必须大于 0 |
| recentCapacity | 5000 | 近期列表容量，必须大于 0；累计计数不受淘汰影响 |

```kotlin
val monitor = LooperMonitor.sMainMonitor
monitor.configureRecording(
    LooperMonitor.RecordingConfig(historyEnabled = true, denseEnabled = true),
)
val listener = object : LooperMonitor.LooperListener {
    override fun onMessageBegin(log: String, beginNs: Long) {
        // 仅执行轻量操作；时间基准是 elapsedRealtimeNanos。
    }
    override fun onMessageEnd(log: String, beginNs: Long, endNs: Long) {
        val durationNs = endNs - beginNs
        // 消费本条消息耗时，不在此执行阻塞 I/O。
    }
}
monitor.register(listener)
val history = monitor.historySnapshot(includeCurrent = false)
val recent = monitor.recentSnapshot()
monitor.clearRecentMessages()
// 页面退出时注销；只有整个共享监控功能退出时才调用 monitor.close()。
monitor.unregister(listener)
```

实际改变配置会清空两类记录，从下一条完整消息生效。历史快照默认可附带执行中消息；
近期清空前已开始的消息不计入新统计周期。状态、回调配对与反射降级详见
[Looper 专题](../docs/knowledge-base/06-Looper消息监控与超时回溯.md)。
