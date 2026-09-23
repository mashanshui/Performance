## 1. 基线和构建边界

- [x] 1.1 记录实施前工作区、源码和测试归属、现有公共接口及模块依赖；交付文件迁移清单，确保未覆盖用户其他改动。
- [x] 1.2 执行当前 `:nativelib:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`，记录结果与已有失败；留存 Manifest、SO 和发布配置基线，失败项不得标成通过。
- [x] 1.3 建立 Core、Transport 的 Android Library 构建配置及后续模块可复用的 SDK/字节码/发布约定；验证新模块可编译且未引入 CMake、Rhea 或 KOOM。

## 2. 共享基础能力与通用运行时

- [x] 2.1 将 RuntimeIdentity、ApplicationMetadata、匿名设备 ID 和进程判断迁入 Core；迁移相关测试并验证主/子进程身份、既有设备 ID 持久化和默认元数据结果。
- [x] 2.2 将 AtomicFileWriter、AndroidNetworkTypeProvider 提取到 Core 并更新所有调用方；通过相关文件队列测试及调用方编译验证不再反向依赖 Crash。
- [x] 2.3 拆出共享事件上下文和传输配置，移除共享元数据对 CrashConfig 的依赖；补充仅 Metrics 配置测试，验证设备 ID、buildId、默认值、无效参数及凭据脱敏。
- [x] 2.4 实现 Core 组件契约与显式装配运行时；通过测试验证重复功能拒绝、相同初始化复用、不同配置/组件冲突、进程准备先于采集、部分失败清理、逆序关闭及幂等行为。
- [x] 2.5 实现 Transport 共享会话，迁移认证、超时、日志与通用结果；通过网络基础测试和所有权测试验证自建资源只关闭一次、外部资源不被误关，Transport 不包含业务 DTO/API/客户端。
- [x] 2.6 调整旧 nativelib 临时装配以使用已提取契约和资源；执行其剩余测试及 App Debug 构建，确认基础模块无旧模块依赖且迁移阶段仍可编译。

## 3. 隔离 Jank、Leak 和原生工具

- [x] 3.1 建立 Jank 模块并迁移配置、Rhea 适配、ZIP 队列、API/DTO/客户端和测试；通过该模块测试及依赖检查验证仅依赖 Core/Transport，Rhea 转换不留在公共层。
- [x] 3.2 为 Jank 提供 SDK 自有事件、状态、导出结果和回调；新增映射测试覆盖现有字段、不支持/非主进程降级、外部已有会话与本次启动的停止归属，公开签名扫描无 Rhea 类型。
- [x] 3.3 建立 Leak 模块，迁移 Activity 检测、KOOM 初始化、report 网络和持久队列；迁移 JVM/仪器测试，通过主机测试验证弱引用重检、report JSON 范围和队列恢复/响应处理。
- [x] 3.4 将 KOOM 分析进程准备接入组件准备阶段；使用可替换边界测试验证检测开关关闭时仍执行必要准备、精简组件列表不调用 KOOM、循环停止遵循所有权。
- [x] 3.5 建立 Native 工具模块，迁移 NativeLib、CMake、Hook/GC/CPU/线程工具和 JNI 规则；构建 arm64 AAR 并核对 SO 名、JNI 导出绑定、Prefab 及该模块的原生任务。
- [x] 3.6 更新临时装配及各模块 consumer rules/Manifest；执行相关模块测试和 App Debug/Release 构建，检查新功能模块不依赖旧 nativelib 或彼此依赖。

## 4. 完成 Crash、Metrics 与 SDK 装配

- [x] 4.1 建立 Crash 模块，迁移剩余 Crash 捕获、配置、队列、协议和测试；通过事件映射、异常路径与上传响应测试，验证精简 Crash 编译不需要其他功能。
- [x] 4.2 建立 Metrics 模块，迁移 FPS、内存指标、MemoryUtils 及其网络代码和测试；验证采样/聚合/队列测试通过，且不存在 Crash、Leak 或 Native 工具依赖。
- [x] 4.3 将 LooperMonitor、LooperDispatchCore、LooperPrinterHook、MessageCollect、MessageTimeoutMonitor 及测试迁入 Metrics；通过 Looper 主机测试和仪器 APK 构建，验证历史/密集记录默认关闭且未引入 JNI。
- [x] 4.4 建立 SDK 聚合模块，组合公开功能配置、默认初始化与能力句柄；通过集成单元测试验证默认值、功能开关、进程降级、共享身份、重复初始化及关闭责任与设计一致。
- [x] 4.5 迁移示例 App 和测试到 SDK/Native 工具依赖，替换监控流程中的第三方公开类型调用；编译全部页面和仪器测试，并确认编译期 buildId 仍传入共享上下文。
- [x] 4.6 删除旧 nativelib 模块声明、生产/测试构建入口和全能 NativeServiceConfig，保留已有文档文件；检查迁移清单无遗漏、无旧单体生产依赖且所有模块 JVM 测试和 App Debug/Release 构建通过。

## 5. 依赖、发布与独立消费者验收

- [x] 5.1 增加依赖边界检查并接入校验任务；验证八模块依赖无环、无功能互依、无 Core/Transport 反向依赖，并以故意违规的测试样例证明检查能够报错。
- [x] 5.2 完成八个库模块统一 Maven 发布配置，以本轮唯一 `performance.version` 执行 `publishToMavenLocal`；逐一核对 AAR、sources JAR、POM、module metadata 及公开 API 所需的依赖作用域。
- [x] 5.3 创建 `verification/modular-consumer/` 独立构建和仅 Crash、仅 Metrics、全量加 Native 工具三个消费者；仅通过发布坐标完成编译与 Release 混淆构建，不使用源码替换或宽泛 SDK keep。
- [x] 5.4 检查两个精简消费者的编译/运行时依赖图、任务图、APK 和清单；确认无 SDK 引入的 Rhea/KOOM/ShadowHook/Native 工具、SO、分析服务及原生构建要求。
- [x] 5.5 检查全量消费者 APK 的原生产物和所有消费者的 R8/Manifest 输出；确认 JNI/反射规则随 AAR 到达宿主、SO 无重复冲突、库不覆盖宿主明文策略，示例自身仍显式配置开发网络策略。
- [x] 5.6 汇总五条上传链路的契约回归，核对本地外部检出的 apm-server 对应文档；记录认证头、端点、字段、响应分类和本地队列行为不变的证据，外部文档不可用时明确列出缺口。

## 6. 设备和混淆运行验证

- [x] 6.1 在支持的 arm64 设备运行三个独立消费者的 Release 初始化/关闭烟测；验证重复初始化、精简身份获取、序列化、队列落盘/重启恢复及全量 JNI 调用，记录设备/API/任务和结果。
- [x] 6.2 运行迁移后的 Looper、FPS、内存和 Activity 泄漏仪器测试，以及示例混淆烟测；按当前 `testBuildType` 选择实际任务并记录通过数，确认未新增监听或线程重复注册。
- [x] 6.3 在全量消费者或示例验证 Rhea 初始化/导出回调与 KOOM dump、分析子进程、report 产物；确认分析进程准备及仅 report 入队，不将本地模拟上传视为真实服务端验收。

## 7. 文档和最终收尾

- [x] 7.1 更新知识库导航、00/01/02/07/08/10/11/12、根 AGENTS.md 结构/命令及既有配置/算法说明入口；评估并更新 03/04/05/06/09/13/14 中受影响内容，逐项对照新模块和公开 API。
- [x] 7.2 将配置指南和 FPS 算法说明迁入知识库，把旧 README 的接入、发布及 JNI 说明并入 15；删除旧 `nativelib/` 目录，检查导航覆盖及全部本地源码/文档链接有效。
- [x] 7.3 汇总本轮主机、依赖、发布、打包、设备验证证据与服务端边界；运行项目校验及 `openspec validate modularize-performance-sdk --strict --no-interactive`、`git diff --check`，设备未执行或失败的任务保持未勾选，交付待审查结果而不自动同步主规格或归档。
