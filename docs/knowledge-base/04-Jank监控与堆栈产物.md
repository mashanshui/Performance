# Jank 监控与堆栈产物


## 2026-09-22 模块化说明

当前 Jank 实现归属 `performance-jank`，公开 SDK 事件和导出结果不暴露 Rhea 类型；设备与服务端验收仍按 12 记录。
[返回导航](README.md)

## 职责边界

本仓库负责接入 Rhea、构造演示卡顿事件、把导出的 ZIP 纳入持久队列并上传。实际采样、二进制编码、ZIP 生成和产物存储由外部 `rhea-inhouse` 负责，本仓库通过 `JankTraceAdapter` 调用。

`PerformanceSdk.initialize` 初始化 Rhea 与 Reporter，不自动注册卡顿检测器。示例首页打开
[TestJankActivity](../../app/src/main/java/com/shanshui/performance/TestJankActivity.kt)，页面不再注册 Looper listener。

## 演示触发链

~~~text
点击场景 → 显示准备执行 → 延迟 100ms
  → elapsedRealtimeNanos 记录开始时间
  → 主线程纯计算 / 休眠 / 分段计算并手工抓栈
  → 记录结束时间，构造单个 JankEvent（阈值 100ms）
  → PerformanceSdk.exportAndEnqueue
  → JankTraceAdapter.exportJankTrace
  → 保存非空有效产物摘要，再入持久队列
  → 后台 JankArtifactUploader.flush
~~~

示例初始化在 `App.JANK_TEST_CONFIG` 固定为同步关闭、异步开启、50ms；SDK 默认值仍为同步开启、异步关闭。
异步采集只保存原始栈，导出时去重并批量符号化由外部 Rhea 1.0.4 实现，本项目不重复实现。
纯异步初始化返回 `INITIALIZING` 时仍建立 Reporter；`isJankAvailable` 仅代表队列可用，实际来源状态通过
`JankDiagnostics.getSyncSamplingStatus()` / `getAsyncSamplingStatus()` 查询，初始化期间禁用触发。

`messageStartNs/messageEndNs` 使用同一单调时钟，`occurredAt` 使用墙上时钟毫秒，`sessionId` 使用当前 SDK 会话。
`attemptedSampleCount` 只统计显式手工抓栈调用，纯计算和休眠均为 0，不推算异步样本数量。
三个场景为 `demo_compute`、`demo_sleep`、`demo_sync_compute`。异步关闭时纯计算不保证有同步样本。

执行与导出期间拦截重复提交。`BUSY` 每 100ms 重试，最多 3 秒，保持事件 ID 不变；其他拒绝不重试。
离页取消未执行负载、状态轮询和导出重试，已受理导出自然完成；回调使用弱引用及运行编号，销毁后不更新页面。
页面不改变全局采集开关，也不停止共享采集器。

## 导出、入队和确认的区别

1. 方法返回的 `ExportRequestResult` 说明导出请求是否被接收。
2. 回调的 `JankExportResult` 说明产物生成结果，携带记录数、被覆盖记录数和入队前保存的文件大小。
3. `queued=true` 说明产物已进入 Reporter 队列。
4. 后续 HTTP 响应与本地删除才完成上传处理。

Reporter 只接收合法命名且确实存在于 Rhea pending 列表中的文件，使用 canonicalFile 核对身份。`SUCCESS` 和 `PARTIAL` 只有在记录数大于 0、文件存在且非空时才可入队；`EMPTY` 与失败不入队；部分产物最终能否被接收，取决于服务端校验。

## 文件状态

`FileJankUploadQueue` 在 `noBackupFilesDir/performance-jank-reporter` 中保存 JSON 元数据；ZIP 保持在 Rhea 管理的位置，路径通过 pending API 获取，不在文档中猜测。

元数据包含 eventId、fileName、createdAtMillis、attempts、nextAttemptAtMillis、deletePending。文件名为 `<eventId>.rheajank.zip`，ID 匹配 `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`。

启动与每次上传前对账：存在 ZIP 而没有记录则补记录，记录对应的 ZIP 已消失则删记录。重试复用原 ZIP 与 eventId，不重新打包。

~~~mermaid
flowchart LR
    Pending["待上传"] --> Send["发送原 ZIP"]
    Send --> Retry["暂时失败：记录重试时间"]
    Retry --> Pending
    Send --> Delete["确认或永久拒绝：deletePending"]
    Delete --> Done["本地删除成功：移除记录"]
    Delete --> LocalRetry["删除失败：只重试本地删除"]
    LocalRetry --> Delete
~~~

## 上传策略与限制

每次 flush 最多处理 5 个产物，遇到重试即结束当前轮次。上传前检查文件名、存在性、非空和单文件上限。磁盘总额度、TTL 和原始文件清理由配置传给 Rhea，元数据队列不另存 ZIP。

当前 Uploader 在 `success=true` 且 status 为 `accepted/duplicate` 时确认；网络层只有 HTTP 200
才进入 Success，201/204 等其他状态保留为 HTTP 错误。永久 HTTP 列表为
`400/401/403/413/415/422`，其中 403 的包名或权限拒绝不会无限重试；503 等暂时错误保留原 ZIP
和 eventId 退避重试。服务端契约见 [协议文档](09-服务端对接与数据协议.md)。

确认和永久拒绝都会标记 deletePending，再通过 `RheaTrace3.deleteJankFile` 删除文件。因此“ZIP 消失”不总表示服务端接受。删除失败时保留状态，下一轮只删本地文件，不重新上传。

## 调试与验证依据

页面查看导出状态、记录数、路径及是否入队；上传日志查看 `jank artifact: eventId=... http=... code=... outcome=...`。Rhea 回调不自动切主线程。展示的是导出时路径，文件之后可能被上传清理。

- [TestJankActivity](../../app/src/main/java/com/shanshui/performance/TestJankActivity.kt)：测试负载、单调时钟边界、阈值与事件构造；[页面测试](../../app/src/androidTest/java/com/shanshui/performance/JankPageInstrumentedTest.kt)覆盖导航、三种负载、时长选择、重复请求拦截和离页取消。
- [JankTraceAdapter](../../performance-jank/src/main/java/com/shanshui/performance/jank/JankTraceAdapter.kt)：外部依赖边界。
- [RuntimeIdentity](../../performance-core/src/main/java/com/shanshui/performance/identity/RuntimeIdentity.kt)：进程级身份来源。
- [JankArtifactReporter](../../performance-jank/src/main/java/com/shanshui/performance/jank/JankArtifactReporter.kt)：初始化、enqueueArtifact、调度。
- [FileJankUploadQueue](../../performance-jank/src/main/java/com/shanshui/performance/jank/FileJankUploadQueue.kt)、[JankArtifactUploader](../../performance-jank/src/main/java/com/shanshui/performance/jank/JankArtifactUploader.kt)。
- [队列测试](../../performance-jank/src/test/java/com/shanshui/performance/jank/FileJankUploadQueueTest.kt)、[上传器测试](../../performance-jank/src/test/java/com/shanshui/performance/jank/JankArtifactUploaderTest.kt)、[网络测试](../../performance-transport/src/test/java/com/shanshui/performance/network/TransportSessionTest.kt)：对账、恢复、确认/重试/永久失败、删除重试、原字节请求。

以下为此前的身份契约验证记录，不替代本次异步页面验证（见 [2026-11-06 构建测试](11-构建测试与本地发布.md#2026-11-06-卡顿采样测试页)）：

上述客户端测试通过替身隔离 Rhea，另有外部 `rhea-inhouse-noop:test` 验证公开 `processId` API 和
UUID v4 校验；本轮已在 EML-AL00/API 29 运行 Rhea 1.0.3 AndroidTest，生成并由 Processor 解析新 ZIP，
同时核对了采样 extra、通用/Jank manifest 和同/异 `processId` 初始化；尚未完成服务端接收联调。
