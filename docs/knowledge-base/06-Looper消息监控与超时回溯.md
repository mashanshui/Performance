# Looper 消息监控与超时回溯

[返回导航](README.md)

## 三个组件的分工

| 组件 | 入口 | 作用 |
| --- | --- | --- |
| `LooperMonitor` | 构造时 setMessageLogging；register/unregister | 将 Printer 日志转成消息开始/结束通知 |
| `MessageCollect` | start / getHistoryMessageQueue / destroy | 合并短消息、记录长消息与空闲间隔，读取时追加当前和 pending 消息 |
| `MessageTimeoutMonitor` | INSTANCE 加显式监听注册；destroy | 协程检查执行中消息是否达到 5 秒，输出主线程堆栈 |

`App` 触发 `LooperMonitor.sMainMonitor` 创建；`MainActivity` 注册卡顿示例监听。当前应用没有自动调用 `MessageCollect.start` 或注册 `MessageTimeoutMonitor`，统一 SDK 也不管理这些监听。

## Looper 回调机制

`LooperPrinter.println` 首次检查日志首字符是否为 `>` 或 `<`，之后按首字符判断开始/结束。`dispatch` 持有监听列表锁并同步调用监听器，执行位置是目标 Looper 线程。

因此监听器自身的磁盘、网络和重计算会进入被观察线程的工作路径。当前实现不隔离单个 listener 的异常，也没有恢复先前 Printer 的关闭接口；与其他 setMessageLogging 使用者并存时，需要专门验证。

## 消息历史

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

示例 MainActivity 的匿名 listener 没有在销毁时解绑，重建 Activity 的影响见 [待处理事项](12-常见问题与待验证事项.md)。

## 源码与验证依据

- [LooperMonitor](../../nativelib/src/main/java/com/example/nativelib/LooperMonitor.kt)
- [MessageCollect](../../nativelib/src/main/java/com/example/nativelib/MessageCollect.kt)
- [MessageTimeoutMonitor](../../nativelib/src/main/java/com/example/nativelib/MessageTimeoutMonitor.kt)
- [MainActivity](../../app/src/main/java/com/example/performance/MainActivity.kt)

当前测试目录未发现这三个组件的专门用例。本轮为源码核对，没有验证 Printer 共存、反射兼容、系统 ANR 或长期运行资源释放。
