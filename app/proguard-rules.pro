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
