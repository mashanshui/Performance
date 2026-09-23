# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Release 仪器 runner 启动时调用此 AndroidX Tracing API；保留该精确类供测试进程解析。
-keep class androidx.tracing.Trace { *; }

# Release 仪器 runner 初始化测试目录时通过 Kotlin lazy 属性访问测试存储。
-keep class kotlin.LazyKt { *; }
-keep interface kotlin.Lazy { *; }

# Release 仪器测试在测试 APK 中引用应用资源类；资源收缩不能删除这些 R 内部类。
-keep class com.shanshui.performance.R { *; }
-keep class com.shanshui.performance.R$* { *; }

# Release 仪器测试与 App 共用 Kotlin 标准库的二进制 ABI；保留其类名和桥接方法，避免测试 APK
# 与混淆后的 App 对同一扩展函数解析出不同签名。
-keep class kotlin.** { *; }

# AndroidX Test 通过 Class.forName 探测该轻量 Future 接口，避免 Release 测试 APK 删除它。
-keep interface com.google.common.util.concurrent.ListenableFuture { *; }

# 设备内存测试调用 startsWith 的默认参数桥接方法，保留 Kotlin 文本扩展类的 ABI。
-keep class kotlin.text.StringsKt { *; }

# 泄漏仪器测试需要进程级强引用真实保留 Activity；R8 不应把仅用于持有对象的测试夹具优化掉。
-keep class com.shanshui.performance.MemoryLeakTestHolder { *; }
