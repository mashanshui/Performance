# SDK 初始化与生命周期

[返回导航](README.md)

## 入口与目的

`PerformanceSdk` 把 Crash、Jank 和 FPS 的启动、配置和资源关闭集中管理。应用在 `Application.onCreate()` 中显式调用；本库自身 Manifest 没有自动初始化 Provider。

~~~kotlin
// appKey 由应用自己的配置来源提供；完整字段见配置指南。
val sdk = PerformanceSdk.initialize(application, appKey, PerformanceConfig())
~~~

参数清单和默认值统一维护在 [配置指南](../../nativelib/CONFIGURATION.md)。`service` 管网络与公共维度，`crash` 管异常事件，`jank` 管 Rhea 和 ZIP，FPS 子配置位于 `jank.fps`。

## 初始化调用链

~~~text
PerformanceSdk.initialize
  → trim App Key，拒绝空白
  → 持有 instance 锁，检查是否已有相同配置实例
  → PerformanceConfig.toNativeServiceConfig
  → ApplicationMetadataResolver.resolve
  → componentFactory.createNetwork
  → 读取/生成共享匿名设备 ID
  → 按开关 startCrash
  → 按开关 initializeJank：Rhea → Jank Reporter
  → 按 FPS 开关与进程条件 initializeFps
  → 保存完整 SDK 实例
~~~

匿名设备 ID 由 Crash 目录下的 `anonymous-device-id` 文件保存，即使仅启用 Jank 也复用这一实现。应用元数据包含包名、版本名和 versionCode，默认 buildId 为 `versionName-versionCode`。Crash 配置可以覆盖其事件元数据，Jank 另有 buildId 覆盖；FPS 使用 Application 元数据和公共派生 buildId。

各 Reporter 的会话标识并非由 SDK 统一分配：Crash 与 FPS 分别生成 sessionId，JankEvent 的 sessionId 由事件构造方提供，示例使用固定演示值。

## 幂等、降级与异常

- 相同 trim 后 App Key 指纹、相等的 `PerformanceConfig` 返回同一实例。配置比较采用数据类相等，不保证“字符串规范化后相同”的不同配置对象也被接受。
- 不同配置或 Key 重复初始化抛出 `IllegalStateException`，不会热更新现有实例。
- 空白 Key 直接抛出 `IllegalArgumentException`。公开配置构造时也可能先抛出 `VALIDATION` 阶段异常。
- 组件失败通过 `PerformanceInitializationException.stage` 表达；枚举包含 `VALIDATION/METADATA/NETWORK/CRASH/JANK/FPS`。
- Rhea 的 `UNSUPPORTED_DEVICE`、`NOT_MAIN_PROCESS` 为预期降级；已启用的 Crash 保持运行。
- `isJankAvailable` 代表 Jank Reporter 就绪；`isFpsAvailable` 代表 FPS Reporter 的开关与进程条件，不代表某个 Window 已开始产生有效帧。
- FPS 不以 `isJankAvailable` 为直接开关：Rhea 平台降级时仍独立检查 FPS 条件；关闭 `jank.enabled` 则同时关闭 FPS。

初始化失败会尝试清理已创建资源；未包装的组件异常阶段由 SDK 根据组件状态推断，不应把阶段字段理解为对底层根因的完整分类。

## 运行时操作

| API | 当前语义 |
| --- | --- |
| `current()` | 返回本进程当前实例，未初始化为 null |
| `flush()/flushAsync()` | 请求后台刷新；不等待上传完成，不跳过重试时间 |
| `pendingCrashEventCount()` | Crash events 队列项数 |
| `pendingJankArtifactCount()` | Jank 元数据队列项数，可能包含待本地删除项 |
| `pendingFpsEventCount()` | FPS 封存事件数，不包含 current.json 和 Helper 当前桶 |
| `setFpsScene(activity, scene)` | 设置场景，null 恢复 Activity 类名；非主线程调用会转发 |
| `exportAndEnqueue(event, callback)` | 请求 Rhea 导出并入队；回调的 queued 不代表服务端确认 |
| `close()` | 解绑、关闭、封存；之后可重新初始化 |

## 关闭与取舍

`close` 使用原子标志保证重复调用无副作用，与初始化共用锁。关闭顺序为 Jank Reporter、FPS、Crash、本 SDK 启动的 Rhea、共享网络。

FPS 关闭时把磁盘工作提交给专用线程，但会通过 `task.get()` 等待，因此调用线程仍可能等待持久化完成。关闭不承诺上传完成；FPS 当前会话的封存事件交给后续初始化上传。Crash close 恢复旧异常 handler 的前提是当前 handler 仍是自己安装的实例。

## 源码与验证依据

- [PerformanceSdk.initialize/rollback/close](../../nativelib/src/main/java/com/example/nativelib/PerformanceSdk.kt)
- [组件工厂](../../nativelib/src/main/java/com/example/nativelib/PerformanceComponentFactory.kt)
- [配置映射](../../nativelib/src/main/java/com/example/nativelib/config/NativeServiceConfig.kt)
- [ApplicationMetadataTest](../../nativelib/src/test/java/com/example/nativelib/ApplicationMetadataTest.kt)、[PerformanceConfigTest](../../nativelib/src/test/java/com/example/nativelib/PerformanceConfigTest.kt)、[NativeServiceConfigTest](../../nativelib/src/test/java/com/example/nativelib/config/NativeServiceConfigTest.kt)

以上测试覆盖纯值和配置边界；本轮没有执行 SDK 重复启动、回滚、多进程或设备生命周期验证。
