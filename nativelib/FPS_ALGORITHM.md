# FPS 统计口径与日志说明

本页统一维护公式与日志口径。收集时机、Activity 生命周期、逐帧调用链、跨线程时序和流程图见
[FPS 收集说明](../docs/knowledge-base/05-FPS采集与场景汇总.md)，其他专题见
[知识库导航](../docs/knowledge-base/README.md)。

## 1. 采集边界

SDK 在 `Activity.onResume` 时为硬件加速的 `Window` 注册 `FrameMetrics` 监听，在
`onPause` 或 `onDestroy` 时停止并结算当前页面。只有 API 24 及以上、主进程和硬件加速
窗口参与采集。`FrameMetrics` 回调没有到达时不补造帧，也不生成零帧事件。

`FIRST_DRAW_FRAME` 会被过滤，`TOTAL_DURATION <= 0` 或刷新率无效的回调也会被过滤。
`dropCountSinceLastInvocation` 只累计到 `callbackDropCount` 诊断字段，不作为掉帧数，
也不会增加 `uiRefreshFrameCount`。

## 2. fps-v1 公式

采集器全程以纳秒保存时长。对刷新率 `R` 的每个有效回调，计算：

```text
帧预算 T = 1_000_000_000 / R
单帧有效耗时 Ci = max(T, TOTAL_DURATION)
activeDurationNs = Σ Ci
uiRefreshFrameCount = N
rawFps = N × 1_000_000_000 / activeDurationNs
normalizedFps60 = min(60, rawFps × 60 / R)
activeDurationMs = ceil(activeDurationNs / 1_000_000)
```

帧预算采用当前设备的实际小数刷新率，落入纳秒整数后再累加。这样 60/90/120 Hz 的
满速页面都归一化为 60；在 60 Hz 下每帧耗时约两个帧预算时，归一化结果约为 30。
页面静止期间没有有效回调，静止时长不会进入分母。

相同会话中，`scene + algorithmVersion + refreshRateHz` 相同的区间合并原始帧数和
有效耗时；刷新率变化会结束旧桶并创建新桶，不能直接平均两个 FPS。一个合并事件的
`occurredAt` 取该桶最早有效样本时间，因此跨页面或跨时段合并时应按这个时间归桶。
当前算法版本固定为 `fps-v1`，不表示与 Bugly 的内部逐帧公式完全一致。

## 3. 日志等级与典型输出

`FpsConfig.logLevel` 默认是 `OFF`。关闭时不会构造逐帧、摘要或队列诊断字符串，也不
会创建每秒摘要任务；上传和持久化仍按配置运行。

- `SUMMARY`：页面生命周期、每秒区间摘要、场景结算、快照和上传结果。
- `VERBOSE`：包含 `SUMMARY`，并打印每帧原始耗时、帧预算、有效耗时、回调丢失数及
  首绘/无效耗时/迟到回调等采纳或过滤原因。逐帧日志有额外开销，性能验收应使用 `OFF`。

示例配置：

```kotlin
PerformanceConfig(
    jank = JankConfig(
        fps = FpsConfig(logLevel = FpsLogLevel.VERBOSE),
    ),
)
```

典型 `SUMMARY` 输出（字段为稳定具名字段，示例值仅用于说明）：

```text
I/FpsHelperV2: fps scope=interval scene=checkout refreshRateHz=60.00 uiRefreshFrameCount=58 activeDurationMs=1000 rawFps=58.00 normalizedFps60=58.00 averageFrameDurationNs=17241379 maxFrameDurationNs=25000000 callbackDropCount=1
I/FpsReporter: upload completed batches=1 acknowledged=1 retried=0 deadLettered=0
```

没有有效刷新时仅重置当前日志区间，不输出区间日志，也不会把这类区间打印成伪造的
`0 FPS`；页面停止后会取消摘要任务。

## 4. 事件可靠性

Helper 在场景或刷新率切换、停止和关闭时，把统计区间交给 Store 合并。Store 默认每 5 秒
原子保存已合并数据到 `current.json`。页面退出只结算并合并，不立即封存；SDK close 时封存
当前会话，或在下一次初始化时恢复并封存上次未封存快照。

事件第一次进入 Store 聚合桶时生成 `eventId`，之后随快照保存，重试一直复用同一 ID。
每秒日志摘要不触发 Store 合并；Helper 当前未结算桶不在 `current.json` 中，异常终止可能丢失
该桶以及尚未写盘的 Store 增量，不能把最多丢失范围概括为 5 秒。

封存事件使用 `/ingest/v1/batches`、`schemaVersion=2`、
`eventType=frame_scene_summary` 上传，只有 HTTP 200 且响应计数与事件错误定位一致时，
`accepted` 或 `duplicate` 事件才会删除。网络失败、异常响应和可重试错误保留原事件，
永久事件错误进入独立 dead-letter 目录。
