## Purpose

规定拆分后的 SDK 如何交付给独立 Android 消费者，确保每个模块的发布依赖、混淆规则、原生库及清单声明能够随产物正确解析。以精简和全量消费者的真实产物验证按需接入成立，避免仅在源码工程内编译成功却无法交付。

## ADDED Requirements

### Requirement: 所有生产模块可以通过发布坐标消费

八个生产模块 MUST 使用统一版本分别发布 Release AAR、sources JAR、POM 和 Gradle module metadata，并声明正确的编译与运行时依赖。完成迁移后 MUST 不再以旧单体产物作为接入前提。

#### Scenario: 独立工程解析本轮发布

- **WHEN** 独立消费者使用本轮唯一版本从 Maven Local 解析 SDK 各模块
- **THEN** 不依赖源码替换、复合构建或旧 nativelib 产物即可编译；所需直接与传递依赖均可由发布元数据解析

#### Scenario: 使用 SDK 的公开配置类型

- **WHEN** 消费者只声明全量 SDK 坐标并使用其公开配置与功能契约
- **THEN** 编译类路径包含所有公开签名需要的类型，无需补充因错误依赖作用域遗漏的内部模块坐标

### Requirement: 精简分发不会带入未选能力

仅 Crash 和仅 Metrics 的发布依赖图及应用产物 MUST 不包含由 SDK 引入的 Rhea、KOOM、ShadowHook 或自有 Native 工具；精简接入 MUST 不要求配置本 SDK 的 CMake/NDK 或 arm64 构建限制。

#### Scenario: 检查精简消费者

- **WHEN** 分别检查仅 Crash 和仅 Metrics 消费者的编译类路径、运行时类路径、任务图、APK 与合并清单
- **THEN** 均无未选择重型能力的依赖、原生构建任务、SO 或分析服务声明

#### Scenario: 检查包含原生能力的消费者

- **WHEN** 消费者选择 Jank、Leak 或 Native 工具并构建支持的 arm64 产物
- **THEN** 所选能力所需原生依赖完整，打包后的共享 SO 无重复冲突，现有 JNI 绑定和加载名称有效

### Requirement: 模块产物携带自身混淆规则

库产物 MUST 向宿主提供其 JNI、网络序列化和本地队列反射所需的 consumer rules。消费者 MUST 无需为整个 SDK 增加宽泛 keep 规则即可使用 Release 混淆版本。

#### Scenario: 精简和全量混淆后运行

- **WHEN** 独立消费者构建并运行 Release 混淆 APK
- **THEN** 已选功能可初始化和关闭，协议 JSON 字段保持稳定，本地队列可写入和恢复，已选 Native 工具的 JNI 调用可用

### Requirement: 宿主保有应用网络策略控制权

发布 AAR MUST 不通过 application 明文流量属性强制覆盖宿主网络策略；权限和服务声明 MUST 随实际能力进入应用，未选能力 MUST 不新增相关服务。

#### Scenario: 宿主显式配置明文策略

- **WHEN** 宿主显式允许或禁止明文流量并合并 SDK 清单
- **THEN** 宿主配置不会被 SDK 的 application 属性覆盖，示例应用仍通过自身显式配置访问其开发地址

### Requirement: 交付文档区分规划与验证事实

模块接入文档 MUST 说明全量和按需依赖、初始化、关闭责任、发布命令及迁移后的测试入口；验证记录 MUST 分别说明主机、设备和服务端执行情况，不能以历史结果替代本次证据。

#### Scenario: 验证环境缺少设备

- **WHEN** 主机检查通过而本轮没有可用的原生能力测试设备
- **THEN** 文档将设备验证标为未执行，相关任务保持未完成，不声称已通过真机或端到端验收

#### Scenario: 迁移后的文档导航

- **WHEN** 使用者从知识库导航、配置指南和算法指南查阅新模块
- **THEN** 配置指南和算法指南可从知识库新路径访问，导航与源码链接指向迁移后的实际文件，且不再指导依赖旧单体产物或旧文档目录
