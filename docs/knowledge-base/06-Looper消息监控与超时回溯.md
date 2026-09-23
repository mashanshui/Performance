# Looper 消息监控与超时回溯


## 2026-09-22 模块化说明

当前 Looper 实现归属 `performance-metrics`，不依赖 JNI；仪器测试和跨 Android 版本兼容仍需独立验收。
[返回导航](README.md)

## 三个组件的分工

| 组件 | 入口 | 作用 |
| --- | --- | --- |
| `LooperMonitor` | of / sMainMonitor；register / unregister / close | Printer 共存与恢复、消息配对计时、有界逐条历史与近期统计 |
| `MessageCollect` | start / getHistoryMessageQueue / destroy | 合并短消息、记录长消息与空闲间隔，读取时追加当前和 pending 消息 |
| `MessageTimeoutMonitor` | INSTANCE 加显式监听注册；destroy | 协程检查执行中消息是否达到 5 秒，输出主线程堆栈 |

`App` 触发 `LooperMonitor.sMainMonitor` 创建；`MainActivity` 注册卡顿示例监听。当前应用没有自动调用 `MessageCollect.start` 或注册 `MessageTimeoutMonitor`，统一 SDK 也不管理这些监听。

## Looper 回调机制

`LooperMonitor.of(looper)` 返回每个 Looper 的共享实例，`sMainMonitor` 每次通过工厂获取主线程实例。首次跨线程创建会向目标线程投递安装任务，状态暂为 `PENDING`；目标线程退出前应主动 `close()`。关闭后再次调用工厂可创建新实例，旧实例拒绝注册和配置。

`LooperPrinterHook` 包装并先转发原 Printer，再交给 `LooperDispatchCore.dispatch` 校验每条日志的 `>` / `<` 首字符。空日志和其他日志仅透传，不影响后续有效消息。包装使用实例级转发保护和监控器级重入保护，第三方保留旧包装时也只分发一次；原 Printer 自身的异常保持原有传播行为。

`register` 按监听器身份去重。每条消息的 `beginNs/endNs` 均由 `SystemClock.elapsedRealtimeNanos()` 获取，所有监听器共享相同区间；忽略重复开始和孤立结束。`isValid()` 只在开始时判断，开始后变为无效仍会配对结束。中途加入者从下一条消息开始接收；注销、关闭以及 Printer 恢复时丢弃未完成状态，不补造结束回调。

回调在目标 Looper 线程同步执行，但不持有监听集合锁；回调中可注册或注销。注销可以取消快照中尚未获准执行的成员，跨线程注销不等待已获准或正在执行的回调完成。单个监听器的普通 `Exception` 被记录并隔离，开始回调抛异常也保留配对结束机会；不吞掉虚拟机级 `Error`。监听器自行负责外部会话收尾，并避免磁盘、网络和重计算。

## Printer 生命周期与平台边界

安装前反射读取 `Looper.mLogging`，只包装已确认的当前值。字段访问失败会记录一次诊断，状态为 `REFLECTION_UNAVAILABLE`，停止该实例的自动安装/恢复；不会盲目调用 `setMessageLogging` 覆盖未知 Printer。`UNAVAILABLE` 表示线程拒绝安装任务或存在另一实例/类加载器的同类包装。`ATTACHED` 是最近一次成功确认的状态，不是实时所有权保证。

在目标线程通过 `Looper.myQueue()` 获取队列，兼容 API 21–22，无需反射 `mQueue`。IdleHandler 按 `uptimeMillis` 至少间隔 60 秒检查；被替换后重新包装当时的 Printer。持续繁忙时可能长期不检查，被替换期间的消息无法补回。恢复会丢弃未完成配对，避免沿用失真的耗时。

`close()` 幂等取消待安装任务、移除 IdleHandler、清空监听和记录并移除实例缓存；只有反射确认当前 Printer 就是自身包装才恢复原 Printer。第三方后来设置的 Printer 不会被覆盖；遗留在其链中的旧包装关闭后仅透传。主线程共享实例通常只在整个监控功能退出时关闭，Activity 仅注销自己的监听。

参考来源为外部检出 `F:/AndroidStudioProjects/matrix/matrix/matrix-android/matrix-trace-canary/src/main/java/com/tencent/matrix/trace/core/LooperMonitor.java`（Tencent Matrix，BSD-3-Clause）；本项目独立实现，不引入 Matrix 依赖或已废弃监听接口。

## 逐条历史与近期统计

使用独立的 `configureRecording(RecordingConfig(...))` 显式开启，字段默认值和示例统一见[配置指南](16-SDK配置指南.md#looper-独立配置)。两项开关互相独立，均不受 `PerformanceConfig` 控制。

历史与近期列表使用有界内存环形队列，每条记录保存开始日志、开始/结束纳秒和耗时，不创建逐消息后台任务。`historySnapshot(false)` 返回已完成历史的不可变副本；默认 `includeCurrent=true` 可额外附加一条执行中消息，`endNs=null`，`durationNs` 为查询时的已执行时间。这一附加项不占已完成队列容量，查询不修改内部历史。

`recentSnapshot()` 的 `messages` 有容量上限；`completedCount/durationNs` 是本统计周期内所有完成消息的累计值，队列淘汰不减去累计值。`clearRecentMessages()` 同时清空近期队列与计数，清空前开始的消息不计入新周期，不影响历史队列。配置值实际变更会清空两类记录，从下一条完整消息开始记录；重复提交相同配置不会清空。

这些统计是消息次数和执行耗时，不自行判断密集阈值，不包含空闲间隔或 CPU 耗时，不上传服务端。

## MessageCollect 聚合历史

`MessageCollect` 以墙上时钟计算耗时，单条消息超过 300ms 时单独记录，短消息累计达到 300ms 后记录聚合，间隔超过 100ms 记为空闲。MessageInfo 的 cpuTime 当前写入 0，不能作为 CPU 时间使用。

历史入队方法设定 200 项上限，但 `collectPendIngMessage` 直接向同一队列追加，绕过该上限；`getHistoryMessageQueue` 会修改并返回内部队列，不是无副作用的快照。

读取 pending 消息通过反射访问 `MessageQueue.mMessages` 与 `Message.next`。当前路径没有捕获反射失败；平台字段、访问限制和并发遍历需要设备验证。

## 超时抓栈

~~~text
onMessageBegin → currentMessageStartTime / targetTimeoutTime
后台 monitorScope → 对齐目标时刻检查
达到阈值 → Looper.getMainLooper().thread.stackTrace → 输出
onMessageEnd → 清空执行中标志
destroy → 取消协程作用域
~~~

阈值为代码内固定 5000ms，长时间未结束的消息会重复检查。它使用墙上时钟与协程 delay，存在调度误差，不是对系统 ANR 判定的复刻，也没有把超时结果传入 Crash/Jank 上传。

如需单独试验，可持有监听实例后显式 register；结束时先 unregister，再 destroy。当前 INSTANCE 是不可重建的 lazy 单例，destroy 取消作用域后再次取 INSTANCE 不会重启监控。

## 与 Jank 的关系与取舍

Jank 演示在消息结束后计算精确消息区间并导出；超时监控在消息仍执行时抓取当下堆栈。两条路径各自存在，当前没有自动融合。消息历史是进程内诊断信息，尚未进入 ZIP manifest 或服务端事件。

示例 MainActivity 持有 listener，在 onDestroy 注销并关闭尚未结束的 Rhea timing；每条正常消息结束都会关闭计时，仅超过 100ms 时输出诊断并导出；Printer 恢复丢失结束边界后，下次开始会先收尾旧会话。MessageCollect 的聚合历史与监控器逐条历史独立存储，不自动启动或相互替代。

## 源码与验证依据

- [LooperMonitor](../../performance-metrics/src/main/java/com/shanshui/performance/LooperMonitor.kt)：共享实例、目标线程安装与关闭。
- [LooperDispatchCore](../../performance-metrics/src/main/java/com/shanshui/performance/LooperDispatchCore.kt)：配对状态、监听快照与有界记录。
- [LooperPrinterHook](../../performance-metrics/src/main/java/com/shanshui/performance/LooperPrinterHook.kt)：Printer 转发、重入保护、空闲检查及所有权恢复。
- [MessageCollect](../../performance-metrics/src/main/java/com/shanshui/performance/MessageCollect.kt)
- [MessageTimeoutMonitor](../../performance-metrics/src/main/java/com/shanshui/performance/MessageTimeoutMonitor.kt)
- [MainActivity](../../app/src/main/java/com/shanshui/performance/MainActivity.kt)

新增 [LooperMonitorTest](../../performance-metrics/src/test/java/com/shanshui/performance/LooperMonitorTest.kt)、[LooperMonitorInstrumentedTest](../../app/src/androidTest/java/com/example/performance/LooperLifecycleInstrumentedTest.kt) 与 [LooperLifecycleInstrumentedTest](../../app/src/androidTest/java/com/example/performance/LooperLifecycleInstrumentedTest.kt)。实际执行结果见 [构建测试](11-构建测试与本地发布.md)；MessageCollect 聚合/pending、超时算法、跨版本反射兼容与系统 ANR 不属于这些用例的证明范围。
