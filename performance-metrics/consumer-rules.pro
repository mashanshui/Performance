# FPS 与内存 DTO/队列字段属于本地和服务端协议，保留字段名和泛型信息。
-keepattributes Signature,*Annotation*,SourceFile,LineNumberTable
-keepclassmembers class com.shanshui.performance.network.** {
    <fields>;
}

# 仪器测试跨 APK 直接访问 LooperMonitor 及其 Companion；保留公开类名和状态机成员。
-keep class com.shanshui.performance.LooperMonitor { *; }
-keep class com.shanshui.performance.LooperMonitor$Companion { *; }
