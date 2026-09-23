# 模块化独立消费者验证

该目录不参与主工程构建，用于验证八个发布坐标能够被外部 Android 工程消费。三个消费者分别覆盖仅 Crash、仅 Metrics，以及全量 SDK 加 Native Tools；依赖只写 `com.example.nativelib:performance-*` 坐标，不引用主工程源码。

## 执行方式

在仓库根目录执行：

```powershell
$env:GRADLE_USER_HOME = (Join-Path (Get-Location) '.gradle-local')
.\gradlew.bat -p verification/modular-consumer assembleDebug assembleRelease `
  '--project-prop=performance.repo=C:/Users/shanshui/.m2/repository' `
  --no-daemon --console=plain --system-prop=kotlin.daemon.enabled=false
```

`performance.repo` 指向本轮 `publishToMavenLocal` 的验证仓库；Rhea 和 KOOM 等本地第三方传递依赖从用户 Maven Local 只读解析。Release 变体启用 R8，消费者工程仅为本地验证复用 `app/sign` 测试签名，不包含发布凭据。

## 验证边界

- Crash 消费者只能解析 Core、Transport、Crash，不携带 Rhea、KOOM、ShadowHook 或原生 SO。
- Metrics 消费者只能解析 Core、Transport、Metrics，不触发 CMake 或 Native Tools 构建。
- Full 消费者解析 SDK 和 Native Tools，验证 JNI SO、consumer rules、R8 与重复 `libc++_shared.so` 合并规则。
- 本目录验证编译、Release 混淆、APK 和清单；三个 `SmokeActivity` 已在 Pixel 4 XL/API 33 上完成初始化/关闭/身份序列化/队列目录烟测，Full 消费者同时验证 JNI。Full 烟测使用关闭 Jank/Memory Leak 的精简配置，真实 Rhea/KOOM 设备链路仍由示例 App 覆盖；客户端真实服务地址联调仍未完成，服务端契约回归记录见项目知识库 09/11。
