# Jank 监控与堆栈产物

[返回导航](README.md)

## 职责边界

本仓库负责接入 Rhea、构造演示卡顿事件、把导出的 ZIP 纳入持久队列并上传。实际采样、二进制编码、ZIP 生成和产物存储由外部 `rhea-inhouse` 负责，本仓库通过 `JankTraceAdapter` 调用。

`PerformanceSdk.initialize` 初始化 Rhea 与 Reporter，不自动替应用注册示例中的 100ms 消息检测器。该检测器目前位于 `MainActivity.onCreate`。

## 演示触发链

~~~text
LooperMonitor.onMessageBegin
  → 保存 elapsedRealtimeNanos 开始时间
LooperMonitor.onMessageEnd
  → 开始值非零，消息耗时 > 100ms
  → RheaTrace3.captureStackTrace(false)
  → 构造 JankEvent
  → PerformanceSdk.current()?.exportAndEnqueue
  → JankTraceAdapter.exportJankTrace
  → SUCCESS/PARTIAL 产物检查
  → FileJankUploadQueue.reconcile
  → 后台 JankArtifactUploader.flush
~~~

`messageStartNs/messageEndNs` 使用同一单调时钟，`occurredAt` 使用墙上时钟毫秒。示例的 `attemptedSampleCount=1` 和 `demo-session` 是演示值。消息结束时调用抓栈，不足以证明整个消息区间具有完整采样，更不能据此推导方法精确耗时。

## 导出、入队和确认的区别

1. 方法返回的 `ExportRequestResult` 说明导出请求是否被接收。
2. 回调的 `exportResult` 说明产物生成结果。
3. `queued=true` 说明产物已进入 Reporter 队列。
4. 后续 HTTP 响应与本地删除才完成上传处理。

Reporter 只接收合法命名且确实存在于 Rhea pending 列表中的文件，使用 canonicalFile 核对身份。`SUCCESS` 和 `PARTIAL` 均可入队；部分产物最终能否被接收，取决于服务端校验。

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

当前 Uploader 在 `success=true` 且 status 为 `accepted/duplicate` 时确认；网络层将有响应体的 2xx 转成 Success，Uploader 没有进一步限定 200。永久 HTTP 列表为 `400/401/413/415/422`，其他错误（包括 403）重试。这与服务端要求的差异见 [协议文档](09-服务端对接与数据协议.md)。

确认和永久拒绝都会标记 deletePending，再通过 `RheaTrace3.deleteJankFile` 删除文件。因此“ZIP 消失”不总表示服务端接受。删除失败时保留状态，下一轮只删本地文件，不重新上传。

## 调试与验证依据

观察 `jank export request submitted`、`jank export completed` 和 `jank artifact: eventId=... http=... code=... outcome=...`，按同一 eventId 关联。Rhea 回调不自动切主线程。

- [MainActivity](../../app/src/main/java/com/example/performance/MainActivity.kt)：消息边界、阈值与事件构造。
- [JankTraceAdapter](../../nativelib/src/main/java/com/example/nativelib/jank/JankTraceAdapter.kt)：外部依赖边界。
- [JankArtifactReporter](../../nativelib/src/main/java/com/example/nativelib/jank/JankArtifactReporter.kt)：初始化、enqueueArtifact、调度。
- [FileJankUploadQueue](../../nativelib/src/main/java/com/example/nativelib/jank/FileJankUploadQueue.kt)、[JankArtifactUploader](../../nativelib/src/main/java/com/example/nativelib/jank/JankArtifactUploader.kt)。
- [队列测试](../../nativelib/src/test/java/com/example/nativelib/jank/FileJankUploadQueueTest.kt)、[上传器测试](../../nativelib/src/test/java/com/example/nativelib/jank/JankArtifactUploaderTest.kt)、[网络测试](../../nativelib/src/test/java/com/example/nativelib/network/NetworkClientTest.kt)：对账、恢复、确认/重试/永久失败、删除重试、原字节请求。

上述测试通过替身隔离 Rhea；本轮没有验证实际 AAR 的 manifest 版本、ART 兼容性、采样覆盖和真实 ZIP 接收。
