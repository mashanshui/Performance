# 客户端任务清单

执行顺序：**服务端阶段 S → 本文件阶段 C（1..5）→ 两边共同阶段 J（第6组）**。按实际实现与验证逐项勾选；本仓库独立阶段只操作 Performance，不代勾服务端或提前归档。

开始前读取 [服务端共同协议和J1..J7矩阵](../../../../../../IdeaProjects/apm-server/openspec/changes/archive/2026-11-08-report-memory-runtime-peaks/design.md) 与 [服务端任务](../../../../../../IdeaProjects/apm-server/openspec/changes/archive/2026-11-08-report-memory-runtime-peaks/tasks.md)（外部检出依赖）。服务端阶段S交接后再切换客户端协议；本仓库只维护本地实现与测试，不重复定义共同字段表。

## 1. 阶段 C：峰值模型与累计

- [x] 1.1 在performance-metrics建立三项独立峰值、区间和原运行元数据模型，并切换MemoryApiModels至共同memory_peak_summary协议；以共同JSON样例进行序列化/反序列化契约测试，验证字段、整数/null、occurredAt及UUID身份，无memorySample混用。
- [x] 1.2 实现可独立测试的逐项累计与首次等值上下文规则，保留MemorySampler的字节口径与独立失败容错；用JVM测试覆盖三次共同采样、各项不同时间/场景、相等值、0、null、全部失败和墙钟回退后的有序区间。
- [x] 1.3 原位同步知识库13的峰值口径与09的协议差异/外部契约引用，00状态仍区分已实现、测试和未联调；检查字段说明与本组契约测试一致，不修改泄漏报告机制。

## 2. 阶段 C：可靠快照与封存存储

- [x] 2.1 在现有内存目录增加完整current快照的可靠读取/替换路径，保证写入失败仍可恢复旧版本，避免共享AtomicFileWriter删除旧文件的回退；通过临时目录和故障注入测试写/同步/替换失败、半文件与备份恢复。如必须修改共享工具，补现有使用者回归且不无关重构。
- [x] 2.2 实现同processId恢复、旧processId先封存后新建的状态机，协调events持久化、current替换及上传，不能把enqueue(false)或配额淘汰当可靠成功；用故障点测试封存前/后/新current前中断、同ID已有一致记录、冲突/损坏、文件失败和重试状态保留。
- [x] 2.3 峰值TTL改为原lastSampledAt计算，默认90天，同步MemoryConfig/服务配置默认常量；配额覆盖current/events/dead-letter并保护唯一有效current，旧memory_sample明确隔离不转换；测试8天/90天边界、封存不续期、重试耗尽、配额不足及不触碰其他目录。
- [x] 2.4 同步知识库08的current/events恢复、13的持久化边界和16的TTL/配额/尝试配置；用本组失败与成功日志确认eventId可关联、失败不声称落盘/封存成功、无密钥/整份隐私内容输出。

## 3. 阶段 C：生命周期、检查点与历史上传

- [x] 3.1 调整MemoryReporter启动顺序，先恢复/封存再采样/上传；新高立即保存，无新高按已有上传周期在采样线程保存最新观测截止；用调度器/采样替身验证无普通样本追加、current不进队列、无主线程IO及关闭期间状态保存边界。
- [x] 3.2 同进程close/reinitialize恢复同轮且旧调度退出后才允许接管；前后台切换/Activity重建保留本轮，子进程不接管目录，初始化与状态变化立即采样且不追赶周期；以生命周期单元/设备测试覆盖身份、调度与并发关闭。
- [x] 3.3 MemoryUploader只选择已封存events，所有触发保留当前轮；复用原响应确认、退避与部分接受，测试手动flush/网络恢复/定时检查、原版本buildId保留、503/408/429/解析错误、响应丢失后duplicate、永久错误与nextAttemptAtMillis，确保确认前不删任务。
- [x] 3.4 核对MetricsComponent和performance-sdk配置/flush/close透传并补必要集成回归，确认不改变FPS/Crash/泄漏组件行为；原位同步知识库08/09/13与00/12中的实际进度及剩余联调边界，本组相关测试通过后记录结果。

## 4. 阶段 C：采样间隔实验与默认值

- [x] 4.1 扩展app的MemorySamplingTimingTest测完整采样、峰值比较、可靠current写盘和检查点路径，记录耗时分位数及写盘次数/字节；运行设备测试验证能覆盖创新高与无新高两条路径，知识库11加入可重复命令和条件。
- [x] 4.2 按客户端design第6节，以60/300秒为基线比较前台5/10/30秒、后台30/60/120秒候选；纳入选择的候选每个前后台至少10分钟同设备同负载，记录CPU、主线程影响、完整路径耗时、写盘及短时上涨捕获，设备不可用则保留未完成，不能以原始读取耗时替代。
- [x] 4.3 根据4.2证据确定短于原默认的前后台间隔，更新MemoryConfig和全部必要透传/配置文档，测试默认值、有效范围、状态切换及延迟不追赶；将唯一性能结果入口放到知识库11并由13/16引用，明确瞬时尖峰和未测功耗边界。

## 5. 阶段 C：客户端独立交接检查

- [x] 5.1 执行 `./gradlew :performance-core:testDebugUnitTest :performance-metrics:testDebugUnitTest :performance-sdk:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`，按实际共享改动补必要回归；解决失败并记录条件跳过，确认构建配置未泄露真实服务凭据。
- [x] 5.2 用fake sender和可控身份/时钟重放共同样例，验证两轮current→封存→上传→确认全过程和中断恢复；输出客户端构建/版本、原eventId与元数据断言及测试条件，明确此结果不等同于真实服务端联调。
- [x] 5.3 检查00/08/09/11/12/13/16及相关导航和外部链接，执行git diff --check与openspec validate report-memory-runtime-peaks --strict；知识库仅记录已验证层级，向联合阶段交接设备、构建和采样频率证据，不提前标端到端完成。

## 6. 阶段 J：等待两端独立阶段后统一测试

服务端S和本仓库C完成后才执行；频率实验允许安排在同次设备会话，须先完成第4组再判定J通过。共同矩阵只维护在服务端design，以下按客户端职责登记结果，不代勾服务端任务。

- [x] 6.1 核对两端版本/契约、测试包名、App Key授权、真实持久库及设备条件，按共同J1/J2验证首次运行不上传、峰值不回退、Activity变化不分轮、杀进程重启封存和中断恢复；以磁盘/请求/服务端eventId一致为通过依据。
- [x] 6.2 执行J3/J4，验证断网/503/响应丢失后保留原任务、合法accepted/duplicate才删除，以及升级重启仍归属原版本/buildId/sessionId/processId；保留可重复自动化和实际设备证据，避免只凭队列数量判断。
- [x] 6.3 配合J5/J6核对设备原事件、服务端入库、summary/trend、Dashboard与MCP的runCount/分位数/独立场景和时间桶；覆盖8天/90天、非法载荷与旧样本，确认当前轮没有提前发送。
- [x] 6.4 复核J7采样频率证据和全部J结论，引用服务端统一验收入口并更新本仓库00/11/12的验证边界；只有两端共同验收通过才可后续分别verify/archive，归档时同步修复跨仓库链接。
