# 运行期内存峰值上报：客户端变更提案

## Why

现有内存 Reporter 每次采样即入队上传，无法满足“一次主进程运行分别保存三项峰值，下次启动后再上报”。用户已确认 PSS、VSS、Java 堆各自取最大值，并要求缩短采样间隔，具体频率结合设备开销确定。

## What Changes

- **BREAKING**：停止产生 `memory_sample`，改为 `memory_peak_summary` / `memoryPeakSummary`；不把旧样本推断成完整运行峰值。
- 主进程复用进程级 `RuntimeIdentity`，同进程 SDK 重建恢复本轮峰值；Activity 重建与前后台切换不分轮。
- 在后台线程累计三项独立峰值并原子保存 `current.json`；保留原版本、buildId、身份及各峰值上下文，不保存全部普通采样明细。
- 新进程启动时先可靠封存上一轮到现有 `events/`，再建立新轮；当前轮不参与自动上传、网络恢复或手动 flush。
- 沿用批量响应确认、稳定 eventId、退避、隔离与磁盘配额，新增快照恢复及封存中断保护。
- 将峰值事件保留窗口规划为 90 天，按原运行观测截止时间计算；准确边界以服务端共同协议为准。
- 扩展现有耗时测试评估前后台候选采样间隔、CPU、写盘和峰值捕获；测量后确定短于当前默认的频率，不将候选值当成已验证默认值。

## Capabilities

### New Capabilities

- `memory-runtime-peaks`：主进程峰值累计、当前快照、跨进程封存、协议上传与采样开销验证。

### Modified Capabilities

无。现有模块化装配与发布规范继续生效，不新增模块或发布机制。

## Impact

- `performance-metrics`：`MemoryReporter`、`MemoryEventStore`、`MemoryUploader`、`MemoryApiModels`、`MemoryConfig` / `MetricsServiceConfig` 及测试。
- 复用 `performance-core` 的身份与原子写入；现有 `AtomicFileWriter` 的删除后重命名回退不满足“始终保留最后有效 current”的要求，须在实现中选用可靠替换路径并覆盖失败场景，不能直接假定已有工具充分可靠。
- `performance-sdk`：仅核对配置透传、初始化/关闭及 flush 边界；`app` 补设备验收与性能测量入口。
- 知识库：00、08、09、11、12、13、16 及相关导航；内存泄漏/HPROF、Crash、FPS、Jank 业务行为不变。
- 来源：[梳理内存信息上报逻辑](codex://threads/01a1b4cb-05cf-7280-b105-3285b76c59b7)。本提案是目标，未实施或执行设备测试。
- 服务端配套：[apm-server 同名变更](../../../../../../IdeaProjects/apm-server/openspec/changes/archive/2026-11-08-report-memory-runtime-peaks/proposal.md)（外部检出依赖）。[服务端 design.md](../../../../../../IdeaProjects/apm-server/openspec/changes/archive/2026-11-08-report-memory-runtime-peaks/design.md) 是共同协议及联合验收唯一正文，客户端设计只补充本地实现。
- 顺序：先执行服务端独立阶段，再执行本仓库独立阶段，最后一起测试；本仓库不代勾服务端任务，联合测试前不归档。
