# FPS 收集说明：执行时机、调用链与流程图


## 2026-09-22 模块化说明

当前 FPS 实现归属 `performance-metrics`，配置使用 `PerformanceConfig.fps` 顶层字段；完整公式仍见配置与算法说明。
[返回导航](README.md)

本文按当前源码说明 FPS 从 SDK 初始化、Window 帧回调到场景结算、快照、封存、上传的全过程。核对日期：2026-09-16。本轮 JVM 测试已覆盖统一身份和 current 快照恢复，未执行设备采集或服务端联调。

## 1. 先明确“FPS 收集”包含哪些阶段

FPS 的逐帧收集由 **Window 的 FrameMetrics 回调驱动**，不是由每秒定时器主动抓取。页面在 resumed 状态、Window 满足条件后注册监听；收到有效帧时更新内存累加器。

后面还有四个独立阶段，不能把它们都称作“上传”：

| 阶段 | 实际工作 | 主要执行者 |
| --- | --- | --- |
| 采集 | 接收并过滤 FrameMetrics，将单帧计入内存 | FpsHelperV2 / FpsFrameAccumulator |
| 结算与合并 | 结束一个场景/刷新率区间，把 summary 合并到当前会话 | FpsHelperV2.emitSummary → FpsReporter.onSummary → Store.merge |
| 快照 | 把 Store 已合并的当前会话数据写入 current.json | FpsEventStore.snapshot |
| 封存 | 将当前会话聚合转换为带稳定 `sessionId`/`processId` 的不可变待上传事件 | sealCurrent / recoverPreviousSession |
| 上传 | 读取已封存事件，发送并按响应确认或重试 | FpsUploader / FpsNetworkClient |

`FpsReporter` 由 `PerformanceSdk` 管理，自动接收 Activity 生命周期。业务可以通过 `setFpsScene(activity, scene)` 设置场景，不需要自己为每个 Activity 调用 Helper.start。

SDK 当前使用 `FpsHelperV2`；同目录的 `FpsHelper` 是独立的滚动/Choreographer 实现，没有接入这条 Reporter 链路。

FPS 与 Jank 的关联在配置层：FPS 开关是 `jank.enabled && jank.fps.enabled`。它不等待 Looper 消息超过卡顿阈值，也不调用 Rhea 抓栈或导出 ZIP。Rhea 返回预期的平台降级后，SDK 仍可继续独立检查 FPS 的可用条件。

## 2. 何时执行：完整触发表

| 触发时刻 | 实际调用 | 执行内容 | 是否结算/落盘/上传 |
| --- | --- | --- | --- |
| Application 中初始化 SDK | initialize → initializeFps → FpsReporter.create | 恢复旧会话，注册生命周期，安排定时任务 | 恢复时封存旧快照；立即安排旧队列上传，尚未为页面注册帧监听 |
| Activity created / started | onActivityCreated / onActivityStarted | 可选生命周期日志 | 不开始采集 |
| Activity resumed | onActivityResumed → mainHandler.post | 延后检查 Window，再 helper.start | 条件满足后开始帧监听 |
| Window 未挂载 | startSamplingAfterWindowAttach | 按 16ms 延时安排再次检查 | 还未采集；受页面仍 resumed 和 Reporter 未关闭限制 |
| 收到 FrameMetrics | onFrameMetricsAvailable → onFrameMetrics | 过滤回调，更新场景与日志两个内存累加器 | 通常不结算、不落盘、不发网络 |
| 有效帧显示刷新率发生变化 | onFrameMetrics → switchRefreshRateLocked | 结束旧刷新率桶，创建新桶，当前帧进入新桶 | 旧桶结算到 Store 内存 |
| 设置不同业务场景 | setFpsScene → helper.setScene | 结算旧场景，更新监听代次并注册新监听 | 旧桶结算到 Store 内存；相同场景名无操作 |
| 采集启动后约每 1 秒 | logIntervalSummary | 有效帧时输出并重置日志区间；无有效帧时静默重置 | 只记录日志；仅 SUMMARY/VERBOSE 启用 |
| Reporter 启动后默认每 5 秒 | scheduleTasks → store.snapshot | 写 Store 当前聚合快照 | 落盘 current.json，不封存 |
| Reporter 启动立即、之后默认每 30 秒 | scheduleTasks → flushInBackground | 处理到期的已封存事件 | 可能上传，不结算 Helper，不封存当前会话 |
| 默认网络变为可用 | NetworkCallback.onAvailable → flushAsync | 快照，再尝试刷新封存队列 | 不绕过 nextAttemptAtMillis |
| 主动 sdk.flush / flushAsync | FpsReporter.flushAsync | 后台快照，再尝试刷新封存队列 | 不读取 Helper 当前桶，不强制封存 |
| Activity paused | onActivityPaused → helper.stop | 移除帧监听，结算当前桶 | 合并到 Store，不立即写事件文件 |
| Activity stopped / 保存状态 | onActivityStopped / onActivitySaveInstanceState | 可选日志 | 不重复结算 |
| Activity destroyed | onActivityDestroyed → helper.close | 兜底结算，关闭 HandlerThread，移除引用 | 已在 pause 结算的桶不会重复输出 |
| sdk.close | FpsReporter.close | 关闭 Helper，取消调度，快照并封存当前会话 | 等待持久化完成；本次关闭不继续上传 |
| 下次初始化 | store.startSession(RuntimeIdentity) → recoverPreviousSession | 从上次 current.json 恢复并生成封存事件 | 新事件使用当前进程身份；旧快照缺少 processId 时不补造，按原任务交给服务端校验 |

1 秒日志使用 Handler.postDelayed；5 秒快照和 30 秒上传使用同一个单线程 scheduler 的 scheduleAtFixedRate。以上都是安排间隔，不是精确执行时间承诺。上传等待、磁盘工作或线程调度会影响实际触发时刻。

完整可配置参数仍统一维护在 [FpsConfig 配置指南](16-SDK配置指南.md)，这里仅列出理解执行时机所需的默认间隔。

## 3. 全链路总览

~~~mermaid
flowchart TD
    A["Application 调用 PerformanceSdk.initialize"] --> B{"FPS 开关开启且进程可用"}
    B -->|否| Z["不创建 FPS Reporter"]
    B -->|是| C["FpsReporter.create"]
    C --> D["startSession：恢复上次 current.json"]
    D --> E["注册 Activity 生命周期与网络回调"]
    E --> F["安排快照与上传任务"]
    E --> G["Activity resumed 后检查 Window"]
    G --> H["FpsHelperV2.start 注册帧监听"]
    H --> I["FrameMetrics 回调：过滤并累计"]
    I --> J["切场景 / 切刷新率 / stop / close"]
    J --> K["emitSummary → onSummary → Store.merge"]
    K --> L["当前会话内存聚合"]
    F --> M["周期 snapshot"]
    L --> M
    M --> N["current.json"]
    N -->|下次初始化恢复| O["events：封存事件"]
    L -->|SDK close：快照并封存| O
    D -->|存在旧快照时| O
    F --> P["启动与周期 flush"]
    O --> P
    P --> Q["FpsUploader → FpsNetworkClient"]
    Q --> R["确认删除 / 保留重试 / 永久隔离"]
~~~

图中从 current.json 到 events 的转换发生在会话恢复或 SDK 主动关闭时；周期 snapshot 本身不会生成待上传事件。

## 4. 初始化与页面监听什么时候开始

### 4.1 SDK 创建 Reporter

生产调用链如下：

~~~text
Application.onCreate
  → PerformanceSdk.initialize
  → 检查 serviceConfig.fpsEnabled && fpsProcessAvailable
  → DefaultPerformanceComponentFactory.initializeFps
  → RuntimeIdentityProvider.current
  → FpsReporter.create
      → 创建 FpsEventStore、FpsUploader、fps-upload scheduler
      → FpsReporter.init
          → store.startSession(RuntimeIdentity)
          → application.registerActivityLifecycleCallbacks(this)
          → registerNetworkRecovery()
          → scheduleTasks()
~~~

`isProcessAvailable` 要求 API 24+ 且进程名等于应用包名。API 24～27 使用 ActivityManager 读取当前进程信息，API 28+ 使用 Application.getProcessName。检查失败时 SDK 将 FPS 进程条件视为不可用。

Store 构造和 `startSession` 的恢复/文件操作直接发生在初始化调用线程。通常应用从 Application.onCreate 调用，所以这部分在主线程；不能把初始化也概括为全部后台执行。

Reporter 初始化只注册后续生命周期回调，没有枚举已经 resumed 的 Activity。因此应在 Application 阶段初始化；若在页面已经恢复后才初始化，当前实现不会主动补发该页面的 resumed 回调。

### 4.2 Activity resumed 后检查 Window

~~~mermaid
flowchart TD
    A["onActivityResumed"] --> B["记录 resumedActivities 为 true"]
    B --> C{"Reporter 可用且未关闭"}
    C -->|否| X["本次不启动采集"]
    C -->|是| D["mainHandler.post"]
    D --> E["startSamplingAfterWindowAttach"]
    E --> F{"仍 resumed 且未关闭"}
    F -->|否| X
    F -->|是| G{"decorView 已挂载"}
    G -->|否| H["按 pending 标记安排 16ms 后检查"]
    H --> E
    G -->|是| I{"硬件加速开启"}
    I -->|否| X
    I -->|是| J["确定业务场景或 Activity 类名"]
    J --> K["helpers.getOrPut：获取该 Activity 的 Helper"]
    K --> L["helper.start：创建线程并注册帧监听"]
~~~

延后检查的目的，是等 Window 挂载后再判断硬件加速。当前重试 runnable 会移除 pending 标记后再次检查，源码不能保证注释所写的“最多一帧重试”；本图保留实际可能重复检查的路径，详见 [L-02 待处理项](12-常见问题与待验证事项.md)。

Reporter 为每个 Activity 保存一个 Helper。`helper.start` 再次检查未 close、未 running 及硬件加速，随后创建/复用 `fps-frame-metrics` HandlerThread，提升 generation，注册 `addOnFrameMetricsAvailableListener(listener, handler)`。

同一 Helper 已 running 时重复 start 无操作；注册失败会回滚 running 和监听引用，后续 resume 可重试。pause 只 stop 并保留 Helper 线程供再次 resume 使用；destroy 才 close 并移除 Helper。

## 5. 一帧到来时的执行链路

### 5.1 回调和过滤

~~~text
Window.OnFrameMetricsAvailableListener.onFrameMetricsAvailable
  → FpsHelperV2.onFrameMetrics
      → 复制 FrameMetrics
      → 读取 TOTAL_DURATION / FIRST_DRAW_FRAME / 当前刷新率
      → 检查 running、generation、Window 身份
      → 过滤首绘、无效耗时和无效刷新率
      → 必要时切换刷新率桶
      → 两个累加器 addCallbackDrops / addFrame
      → 有旧刷新率桶时 emitSummary
      → VERBOSE 时输出本帧日志
~~~

~~~mermaid
flowchart TD
    A["FrameMetrics 回调"] --> B["复制指标并读取耗时与刷新率"]
    B --> C{"running 且代次与 Window 匹配"}
    C -->|否| X["忽略回调"]
    C -->|是| D{"首绘或耗时非正或刷新率非正"}
    D -->|是| X
    D -->|否| E{"首次有效帧"}
    E -->|是| F["建立当前刷新率的两个累加器"]
    E -->|否| G{"刷新率变化超过 0.01 Hz"}
    G -->|是| H["保存旧场景桶快照并建立新桶"]
    G -->|否| I["更新回调丢失诊断计数"]
    F --> I
    H --> I
    I --> J["当前帧同时加入场景桶与日志桶"]
    J --> K{"本次有旧桶快照"}
    K -->|是| L["锁外 emitSummary → Store.merge"]
    K -->|否| Y["完成回调；可选逐帧日志"]
    L --> Y
~~~

整个逐帧处理在注册时指定的 HandlerThread 上执行。`generation` 表示一次监听代次；场景切换、停止后旧回调即使已经排队，也会被身份检查挡住，避免计入新的场景。

Helper 先在自己的锁内更新数据，再在锁外调用 emitSummary。无效回调在 addFrame 前被过滤，不增加有效帧数；callbackDropCount 也只在采纳路径中累计，不自动换算成补偿帧。

### 5.2 两个累加器为什么并存

| 累加器 | 保存范围 | 何时清空 | 输出去向 |
| --- | --- | --- | --- |
| sceneAccumulator | 本次连续采集中的同场景、同刷新率区间 | 切场景/刷新率、stop、close | summaryListener → Store |
| intervalAccumulator | 自上次日志结算后的区间 | 每秒日志 snapshotAndReset，或生命周期/分桶变化 | 仅 interval 日志 |

每个有效帧同时调用两者的 addFrame。算法会计算该刷新率的帧预算，取“预算与 TOTAL_DURATION 中较大者”作为有效耗时，然后更新有效帧数、累计时长、最大有效耗时和首末样本时间。

这些字段用于不同观察尺度：每秒日志可以及时观察页面，而场景桶保留整个连续区间。日志的 reset 不会重置场景累计，因此不会把场景数据按秒截断。

## 6. 场景如何结算并进入当前会话

### 6.1 结算条件

~~~text
onActivityResumed
  → start → 收到有效帧 → sceneAccumulator 累计
  → setScene / 刷新率变化 / stop / close
  → FpsFrameAccumulator.snapshot
  → FpsHelperV2.emitSummary
  → 创建 Helper 时传入的 summaryListener
  → FpsReporter.onSummary
  → FpsEventStore.merge
~~~

只在有有效帧时才有 summary；空桶 snapshot 返回 null。Reporter 还会检查 acceptingSummaries 和帧数，再执行 Store.merge。

`setFpsScene` 在主线程更新；非主线程调用会先 post 到主线程。设置不同场景且 Helper 正在运行时，setScene 保存旧桶、移除旧监听、提升 generation、为新场景创建累加器并重新注册监听，然后输出旧桶。相同场景名直接返回；Helper 尚未开始时只保存场景，等待 resume。

刷新率改变时，旧桶快照不包含触发变化的当前帧；当前帧计入新刷新率桶。Helper 以超过 0.01Hz 判定切桶，Store 合并键中的刷新率再格式化为三位小数。

### 6.2 会话合并规则

Store 使用 `scene + algorithmVersion + normalizedRefreshRate` 作为当前会话中的桶标识。同一 key 第一次合并时生成 eventId，后续合并复用 ID，累加原始帧数和纳秒时长、取最大耗时、保留最早和最晚样本时间。

例如同一运行先后进入两次 checkout，且算法与刷新率相同，两段区间最终合并成一个会话事件；返回页面会重新开始 Helper 桶，但不会因此自动创建另一个 Store 事件。

Store.merge 直接在调用方线程执行，并非总是 fps-frame-metrics：刷新率变化从采集线程调用，pause/切场景通常从主线程调用，SDK close 则取决于其调用线程。它只合并内存，但 Store 的同步锁也被快照/封存使用，因此仍可能等待其他线程释放锁。

## 7. 统计口径

完整公式和日志字段以 [FPS 算法说明](17-FPS算法说明.md) 为准。关键语义是有效帧数除以有效渲染时长，再按刷新率归一化到 60；静止期间不补造帧，也不把等待时间填入分母。

`dropCountSinceLastInvocation` 仅是回调丢失诊断，不补进刷新帧数。相同会话内按 `scene + algorithmVersion + refreshRateHz` 合并原始计数和纳秒时长，不能直接平均 FPS。算法版本 `fps-v1` 与网络 schema v2 是不同维度。

场景名非空、最多 128 字符且不含控制字符。默认使用 Activity 完整类名；同一业务场景可跨页面合并，动态订单号等不适合作为场景维度。

## 8. 快照、封存和上传什么时候发生

数据位置为 `noBackupFilesDir/performance-fps-reporter`：

| 状态 | 内容 | 进入后续状态的时机 |
| --- | --- | --- |
| Helper 内存桶 | 当前尚未结算的帧 | 场景/刷新率切换、停止或关闭 |
| Store 内存聚合 | 已结算区间按场景合并，首次合并生成 eventId | 周期 snapshot 或 flush 保存快照 |
| `current.json` | 已进入 Store 的聚合快照 | 下次 startSession 恢复，或 SDK close 主动封存 |
| `events/*.json` | 不可变上传事件与重试状态 | 启动、周期、网络恢复和手动 flush 上传 |
| `dead-letter` | 永久拒绝或损坏数据 | 不进入正常重试队列 |

**页面 pause 只结算到 Store，不立即封存为上传事件。** `flushAsync` 保存 Store 快照并上传已有封存事件，不强制结束当前会话，也不会主动读取 Helper 当前桶。

每秒 SUMMARY 日志仅重置日志区间累加器，不调用 Store merge。因此定期写 current.json 不表示正在运行的长页面每一帧都已落盘；异常终止可能丢失尚未结算的整个桶以及尚未写盘的 Store 增量。

SDK close 会关闭 Helper、接收最后的 summary，再快照并封存；它等待持久化任务完成，但把上传交给下一次初始化。启动恢复 current.json 时复用其 eventId，不因重启生成新的事件 ID。

### 8.1 跨线程时序

下面展示页面采集、pause 结算和下次启动上传的一条典型路径。定时任务实际可能交错；图中“5 秒”表示默认安排间隔。

~~~mermaid
sequenceDiagram
    participant M as 主线程/SDK
    participant R as FpsReporter
    participant H as fps-frame-metrics
    participant S as FpsEventStore
    participant U as fps-upload
    participant N as 网络客户端
    M->>R: 初始化
    R->>S: startSession 恢复旧快照
    R->>U: 安排立即上传、5秒快照、30秒上传
    M->>R: onActivityResumed
    R->>M: post 检查 Window
    M->>H: Helper.start 注册监听
    loop 每次有效 FrameMetrics 回调
        H->>H: 更新 scene 与 interval 累加器
    end
    opt SUMMARY 或 VERBOSE 开启
        H->>H: 每秒输出 interval 日志并仅重置日志桶
    end
    U->>S: snapshot
    Note over H,S: 此时只保存已进入 Store 的聚合，不读取 Helper 当前桶
    M->>R: onActivityPaused
    R->>R: Helper.stop 移除监听并提取 summary
    R->>S: onSummary → merge，仅合并内存
    U->>S: 下一次 snapshot 写 current.json
    Note over M,U: 进程退出后，本次运行的任务不再执行
    M->>R: 下次 SDK 初始化
    R->>S: 恢复 current.json，封存为 events
    R->>U: 安排立即 flush
    U->>S: peekBatch 读取到期事件
    U->>N: sendBatch
    N-->>U: HTTP 与批次响应
    U->>S: 确认删除 / 重试 / dead-letter
~~~

图中的 Store 是共享对象，不是独立线程。箭头表示调用关系；merge 在触发它的线程执行，周期 snapshot 在 fps-upload 上执行。

### 8.2 主动关闭的路径

`FpsReporter.close` 按以下顺序处理：

1. 原子设置 closed，注销 Activity 生命周期和网络回调。
2. 遍历 Helper.close，接收最后的 summary；此时 acceptingSummaries 仍为 true。
3. 清理 Helper/场景/页面状态，随后关闭 summary 接收。
4. 取消快照与上传任务，关闭 fps-upload scheduler。
5. 向 fps-persistence 提交 `store.snapshot → store.sealCurrent`。
6. 调用 `task.get()` 等待持久化任务；随后 SDK 继续释放共享网络资源。

因此“磁盘工作在后台线程”不表示 close 立即返回。此路径不主动发最后一批网络请求，封存事件等待后续初始化。

### 8.3 上传执行链

~~~text
启动 / 周期 / 网络恢复 / 手动刷新
  → FpsReporter.flushInBackground
  → flushInProgress 防并发 + closed 检查
  → runBlocking { FpsUploader.flush() }
      → expireOlderThan
      → selectBatch → Store.peekBatch
      → sender.send → FpsNetworkClient.sendBatch
      → FpsIngestApi.ingest
      → POST ingest/v1/batches
      → handleResult → acknowledge / markRetry / moveToDeadLetter
~~~

flush 每轮最多处理 5 批，按 FpsConfig 限制事件数量和完整 JSON 大小，只选择重试时间已到的事件。Reporter 的 runBlocking 在 fps-upload 上等待结果；底层 HTTP IO 由网络客户端调度，不在 Window 帧回调中上传。

## 9. 线程与生命周期对照

| 执行位置 | 工作 | 需要注意 |
| --- | --- | --- |
| SDK 初始化调用线程，通常主线程 | create、Store 初始化与旧快照恢复 | 包含同步磁盘工作；注册回调后才观察后续页面生命周期 |
| 主线程 | Activity 回调、Window 检查、监听注册/移除、业务切场景 | pause/切场景时的 summary 合并也在该调用线程 |
| 每个 Helper 的 fps-frame-metrics 线程 | FrameMetrics、刷新率切桶、每秒日志 | pause 保留线程，destroy/close 使用 quitSafely；线程名相同不代表全局只有一个 |
| fps-upload 单线程调度器 | 定时快照、手动刷新、上传循环 | 快照和等待网络结果共用此线程，执行可能相互延迟 |
| fps-persistence 单线程执行器 | SDK close 时的最终快照与封存 | close 调用方等待任务完成 |
| 系统网络回调线程 | onAvailable 调用 flushAsync | 实际快照/上传转交 scheduler |

Activity 暂停会停止该页面采集，但不会关闭 Reporter 的周期快照和上传任务。只要应用进程仍在执行、Reporter 未关闭，后台也可能处理旧封存队列。

## 10. 用一次页面访问理解数据变化

以下是假设场景与刷新率始终不变的说明性过程，时间仅用于演示，不是设备实测：

| 时点 | 操作 | 数据实际在哪里 |
| --- | --- | --- |
| 第 0 秒 | SDK 初始化，页面 resume 并开始滚动 | Helper 建立当前桶；Store 当前会话初始为空 |
| 第 1～4 秒 | 连续帧回调，SUMMARY 每秒输出 | 帧在 Helper 累加器；每秒日志不把它们交给 Store |
| 第 5 秒 | 定时 snapshot | 若未切场景/刷新率或停止，Store 仍没有这段帧；快照也不含它 |
| 第 8 秒 | 页面 pause | Helper.stop 输出整段 summary，Store.merge 生成/更新会话桶 |
| 第 10 秒 | 下一次定时 snapshot | 已结算的区间写入 current.json，但 pendingFpsEventCount 仍可为 0 |
| 第 30 秒 | 周期上传 | 只处理 events；不会因为间隔到了就发送上述 current 数据 |
| 后续启动 | 恢复 current.json | 封存为 events，启动上传任务才可发送这一会话 |

如果第 6 秒进程异常终止，尚未结算的 Helper 桶可能整体丢失；如果第 9 秒异常终止，则第 8 秒刚合并、还未落盘的 Store 增量也可能丢失。通过这些边界可以解释“有日志但服务端没有数据”和“配置 5 秒快照却丢失超过 5 秒”的现象。

## 11. 响应、容量与取舍

`FpsUploader` 要求 HTTP 200；若返回 requestId 则必须匹配，accepted/duplicate/rejected 非负且总数等于批次数，错误条目数应等于 rejected，错误定位不能重复或矛盾。通过校验后才确认未拒绝事件，临时错误保留 ID，永久错误隔离。

队列 TTL 与容量由 `FpsConfig` 控制。容量统计只覆盖 events 目录，按文件修改时间淘汰最旧事件，不覆盖 current.json 和 dead-letter 的总大小。

聚合减少了上传量，但失去了每帧完整明细。当前 frameSceneSummary 上传场景、算法、活跃时长、刷新帧数、刷新率和归一化 FPS；模型中的 histogram 可选字段当前没有由 Reporter 填充，日志里的最大耗时也不等于协议必传字段。

## 12. 使用、源码导航与验证

测试入口见 [示例调试](10-示例应用与调试路径.md)。调试打开 SUMMARY，逐帧定位时临时用 VERBOSE；性能验收使用 OFF。

- [FpsReporter](../../performance-metrics/src/main/java/com/shanshui/performance/fps/FpsReporter.kt)：生命周期、onSummary、flushAsync、close。
- [FpsHelperV2](../../performance-metrics/src/main/java/com/shanshui/performance/fps/FpsHelperV2.kt)：采集与算法；[FpsEventStore](../../performance-metrics/src/main/java/com/shanshui/performance/fps/FpsEventStore.kt)：merge/snapshot/sealCurrent/recoverPreviousSession。
- [FpsUploader](../../performance-metrics/src/main/java/com/shanshui/performance/fps/FpsUploader.kt)：响应校验。
- [PerformanceSdk](../../performance-sdk/src/main/java/com/shanshui/performance/PerformanceSdk.kt)：initialize 中 FPS 创建条件、setFpsScene、flushAsync、close。
- [PerformanceComponentFactory](../../performance-core/src/main/java/com/shanshui/performance/core/PerformanceComponent.kt)：initializeFps 到 FpsReporter.create 的生产入口。
- [PerformanceConfig](../../performance-sdk/src/main/java/com/shanshui/performance/PerformanceConfig.kt)：顶层 `fps` 配置映射到 Metrics 组件，已移除旧 `NativeServiceConfig`。
- [FpsNetworkClient](../../performance-metrics/src/main/java/com/shanshui/performance/network/FpsNetworkClient.kt)与[FpsIngestApi](../../performance-metrics/src/main/java/com/shanshui/performance/network/FpsIngestApi.kt)：schema v2、批次请求与网络结果。
- [FpsHelperV2Test](../../performance-metrics/src/test/java/com/shanshui/performance/fps/FpsHelperV2Test.kt)：刷新率归一化、过滤、诊断计数。
- [FpsEventStoreTest](../../performance-metrics/src/test/java/com/shanshui/performance/fps/FpsEventStoreTest.kt)：同桶合并与恢复；[FpsUploaderTest](../../performance-metrics/src/test/java/com/shanshui/performance/fps/FpsUploaderTest.kt)：duplicate 确认与计数不匹配重试。

本轮未执行真实 FrameMetrics、异常退出丢失窗口或设备刷新率切换验证；页面仪器测试仅证明入口和控件的断言范围，不证明 FPS 数值准确。
