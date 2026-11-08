# 内存运行峰值客户端核验报告

核验日期：2026-11-08。变更：`report-memory-runtime-peaks`，流程：`spec-driven`。

## 结论

完整性、正确性与设计一致性检查均已执行，CRITICAL 0、WARNING 0、SUGGESTION 0，可同步正式规格并归档。核验使用当前源码和同一任务2026-11-07的JUnit、设备、频率与联合证据；本次没有重跑80分钟实验，也未覆盖安装或部署。

| 维度 | 结果 |
|---|---|
| 完整性 | 21/21任务；1份增量的7项新增需求均有实现 |
| 正确性 | 7/7需求完成映射，18/18场景已检查；纯逻辑故障注入、真实设备生命周期与共同J1..J7互补 |
| 一致性 | 原运行身份、三项独立峰值、current隔离、先封存、确认后删除、原截止TTL及10/30秒默认值符合设计；保留未观测与功耗边界 |

## 实现与场景映射

下列生产路径以`performance-metrics/src/main/java/com/shanshui/performance/`为前缀，测试路径以同模块`src/test/java/com/shanshui/performance/memory/`为前缀。

| 需求 | 场景数 | 实现与验证依据 |
|---|---:|---|
| 主进程一轮运行保持稳定身份 | 3 | `memory/MemoryReporter.kt:359`主进程门禁及互斥接管；`MemoryEventStore.kt:54`同processId恢复。MemorySamplingLoopTest.reinitializeWaitsForOldWriterAndFinalCheckpoint、MemoryRuntimePeaksTest.twoRunsKeepCurrentPrivateAndUploadOriginalMetadata及MemoryPeakLifecycleTest覆盖SDK重建、Activity变化和进程谓词；初次无快照不造历史事件 |
| 分别累计三项观测最大值 | 2 | `memory/MemoryPeakAccumulator.kt:16`逐项比较、真实0/缺失、首次等值上下文和回退墙钟；MemoryRuntimePeaksTest.independentPeaksEqualZeroMissingAndClockRollback核验共同三次输入、时间和上下文 |
| 当前轮快照可靠持久化且不上传 | 3 | `memory/MemoryAtomicFile.kt:28`写入/同步/备份/提交，`MemoryRunRecorder.kt:25`即时新高及检查点；MemoryReporter采样/上传线程分开。atomicWriteSyncReplaceAndCommitFailuresKeepOldSnapshot、pendingPeakRetriesAndCheckpointAdvancesEndWithoutQueueing、日志脱敏、fake sender及共同J1/J2覆盖flush、替换中断、无新高持续观测 |
| 下次启动先封存上一轮 | 3 | `memory/MemoryEventStore.kt:54`旧身份可靠封存及同ID内容核对、保护current；interruptedSealReusesExistingRecordAndPreservesRetries、sealFailureAndQuotaDoNotOverwriteCurrent、newCurrentFailureAfterSealKeepsBothRunBoundaries、损坏/冲突隔离测试及真实SIGKILL的J2覆盖各边界 |
| 历史补报遵守共同协议与确认边界 | 3 | `network/MemoryApiModels.kt`只提供memory_peak_summary，`memory/MemoryUploader.kt:36`选择封存任务并验证响应/退避；共享fixture round-trip、twoRuns、transientFailuresAndLostResponsesKeepPayloadUntilDuplicate及既有上传部分接受/非法响应测试；J3/J4实际503、丢响应、duplicate和覆盖升级保留原版本 |
| 保留和配额不得使历史事件续期 | 2 | `memory/MemoryEventStore.kt:206`按原lastSampledAt过期，`:295`配额保护唯一current，重试上限隔离；ttlUsesOriginalEndAndDoesNotRenewOrDeleteActiveCurrent、sealFailureAndQuotaDoNotOverwriteCurrent、quotaIncludesCurrentAndDeadLetterButNeverOtherDirectories及旧任务/耗尽记录测试覆盖8天、恰好90天与空间不足 |
| 缩短采样间隔须验证完整路径开销 | 2 | `memory/MemorySamplingLoop.kt:18`状态切换立即采样、串行且不追赶；`metrics/MetricsConfig.kt`默认前台10秒/后台30秒。delayedSamplingDoesNotCatchUpAndSwitchIsImmediate、memoryRetentionDefaultsAndIntervalValidation，以及MemorySamplingTimingTest/MemoryPeakFrequencyTest的完整路径与八段各600秒实验支持默认选择 |

## 测试和联合边界

现存JUnit为core8、metrics64、SDK7、App14，共93项，失败/错误/跳过0。此前同任务Debug、Debug仪器与Release构建均通过；最终正常构建的联合开关false、地址覆盖为空、默认App Key为公开占位值。设备独立回归4项，联合准备测试另1项，不能混算为一次套件。

完整路径120次采样的P50/P95/P99为1.660/1.896/2.131ms。频率实验为同一目标APK的八段各600秒，模拟器中断后校验相同APK哈希再补五段；依据CPU、心跳、写盘和上涨窗口观测选择10/30秒。唯一完整性能入口是本仓库`docs/knowledge-base/11-构建测试与本地发布.md`，此处不复制频率矩阵。

共同协议及J矩阵只维护于配套apm-server归档的design；真实SDK、隔离Servlet/ClickHouse、升级和故障网络、HTTP/Dashboard/MCP核对的唯一证据入口为配套仓库`docs/validation/memory-runtime-peaks/README.md`。已核对J1..J7和原数据/APK恢复断言，不把fake sender或构建当作真实网络证据。

用户明确选择Pixel_6/API35/arm64模拟器。顺序实验心跳不等于绘制帧因果，不能宣称无主线程影响；真机功耗、跨ROM、满配额吞吐、突然断电和瞬时尖峰全覆盖仍未验证。上述为声明边界，无适用的核验检查因材料不可用而跳过。

## 正式规格同步检查

新建`memory-runtime-peaks`正式规格，复制增量Purpose和7项新增需求，转换为单一Requirements节；不复制ADDED操作标题，不改其他客户端能力。同步后执行正式规格严格校验，并修复两个归档目录移动导致的相对链接。
