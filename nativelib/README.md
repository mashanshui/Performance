# nativelib 本地发布

项目机制和源码调用链见[知识库导航](../docs/knowledge-base/README.md)，开发规范见
[AGENTS.md](../AGENTS.md)。Crash 客户端已按服务端 schema v2/`packageName`/UUID v4 `processId` 契约同步，
接入前请查阅[协议对照](../docs/knowledge-base/09-服务端对接与数据协议.md)；本文的发布和初始化
示例不代表已完成修复后的真实服务端联调。

`nativelib` 已配置 Android Library 的 Maven 发布能力，默认发布 `release` 变体。

完整的默认值、字段说明、校验限制和运行时控制方式参见
[自定义配置指南](CONFIGURATION.md)。

## 统一初始化

应用只需要在 `Application.onCreate()` 中传入 `Application` 和 App Key：

```kotlin
val performance = PerformanceSdk.initialize(this, appKey)
Log.i("Performance", "jankAvailable=${performance.isJankAvailable}")
```

Crash 和线上卡顿默认同时启用。服务地址、环境、渠道、网络超时、Crash 批量参数以及
Rhea 采样参数都可以通过统一配置覆盖：

默认值为：服务地址 `http://192.168.0.150:8080`、环境 `debug`、渠道 `official`、
网络连接/读写超时 3 秒且关闭网络日志；Crash 每批 20 条、单批 512 KiB、每 30 秒上传；
Jank 使用 5 MiB 缓冲区、10 ms 采样间隔、20 MiB 磁盘额度、3 天 TTL、10 MiB 单文件限制，
仅前台采集，Hook、对象分配和统计均关闭。

```kotlin
val performance = PerformanceSdk.initialize(
    application = this,
    appKey = appKey,
    config = PerformanceConfig(
        service = ServiceConfig(
            baseUrl = "https://apm.example.com",
            environment = "release",
            channel = "official",
        ),
        crash = CrashConfig(
            uploadIntervalMillis = 60_000L,
        ),
        jank = JankConfig(
            minSampleIntervalMillis = 5L,
            enableStackCaptureStats = true,
            mappingId = "mapping-2026-09",
            fps = FpsConfig(
                logLevel = FpsLogLevel.SUMMARY,
            ),
        ),
    ),
)
```

FPS 由 SDK 自动跟随 Activity 的 `onResume`/`onPause` 采集。它只统计真实 UI 刷新帧，
按场景和刷新率在一次运行内合并，下一次启动时封存并上传 `frame_scene_summary` v2。
需要业务场景名时调用 `performance.setFpsScene(activity, "checkout")`，传 `null` 恢复
Activity 完整类名。API 24 以下、非主进程或非硬件加速 Window 会跳过 FPS 采集。

FPS 日志默认关闭。`SUMMARY` 输出页面生命周期、每秒统计摘要、场景合并和上传结果；
`VERBOSE` 额外输出逐帧耗时及过滤原因。逐帧日志会增加开销，性能验收应使用 `OFF`。
日志等级和批次、快照、队列参数位于 `JankConfig.fps`，完整字段见
[配置指南](CONFIGURATION.md) 的 FPS 小节。
统计公式、刷新率切桶、持久化边界和典型日志见 [FPS 算法说明](FPS_ALGORITHM.md)。

包名、版本名、versionCode、buildId 和匿名设备 ID 默认从当前 Application 自动解析或持久化，
不需要应用层重复维护。SDK 还会为当前 Android 进程生成只读 UUID v4 `sessionId`/`processId`：
主进程的 `processId` 等于 `sessionId`，子进程独立生成；Crash、Jank、FPS、内存和泄漏 report
metadata 共用这组身份，队列重试不重新生成。构造 JankEvent 时使用 `performance.sessionId`。
设备不满足 Rhea 的线上采集条件时，`isJankAvailable` 为 `false`，
Crash 仍会继续工作；配置错误或真正的初始化错误会抛出
`PerformanceInitializationException`。

导出卡顿时使用 SDK 门面，导出成功的 ZIP 会自动写入持久上传队列：

```kotlin
val requestResult = performance.exportAndEnqueue(event) { result ->
    Log.i("Performance", "queued=${result.queued}")
}
```

SDK 队列和网络日志不保存 App Key 明文，认证时通过运行期请求头使用。示例构建脚本提供
`performance.appKey` 到 `BuildConfig.PERFORMANCE_APP_KEY` 的注入入口，但当前 App.kt 初始化
仍使用字符串常量，没有读取该字段；修改 Gradle 属性尚不能改变该调用的 Key。接入方式和
当前差异见[配置指南](CONFIGURATION.md)与[示例调试](../docs/knowledge-base/10-示例应用与调试路径.md)。
直接把 App Key 写入 APK 仍不能阻止逆向提取，生产环境应按服务端密钥策略管理。

## Release 混淆

`nativelib` 的 Release AAR 保持未混淆，由消费者应用的 R8 在最终 APK/AAB 构建阶段统一处理。
AAR 通过 `consumer-rules.pro` 传递 JNI 名称、Gson 网络字段和持久化队列记录字段所需的精确保留规则，
并保留泛型、注解及源码行号属性；不会对整个 SDK 或第三方库添加宽泛规则。启用 R8 的消费者无需
复制规则文件，但应确认 AAR 内的 `proguard.txt` 已随发布产物提供。

应用的 Release 映射文件位于 `app/build/outputs/mapping/release/mapping.txt`。发布时必须把 mapping
与对应 APK 一并留存；每次重新构建都会生成新的映射，不能用其他版本的 mapping 还原线上堆栈。
Release 配置与设备烟测方式见[构建测试与本地发布](../docs/knowledge-base/11-构建测试与本地发布.md)。

## 发布到 Maven Local

在项目根目录执行：

```powershell
.\gradlew.bat :nativelib:publishToMavenLocal --no-daemon
```

默认坐标为：

```text
com.example.nativelib:nativelib:1.0.0
```

也可以在发布时覆盖坐标：

```powershell
.\gradlew.bat :nativelib:publishToMavenLocal `
    -Pnativelib.groupId=io.example `
    -Pnativelib.artifactId=nativelib `
    -Pnativelib.version=1.0.1 `
    --no-daemon
```

产物默认位于当前用户的 Maven Local 仓库：

```text
%USERPROFILE%\.m2\repository\com\example\nativelib\nativelib\1.0.0\
```

## 本地消费者配置

消费者的仓库列表需要包含 `mavenLocal()`，并声明发布坐标：

```kotlin
repositories {
    mavenLocal()
    google()
    mavenCentral()
}

dependencies {
    implementation("com.example.nativelib:nativelib:1.0.0")
}
```
