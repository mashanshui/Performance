# Gson、Retrofit 与本地队列通过反射读取字段；保留泛型、注解和崩溃行号信息。
-keepattributes Signature,*Annotation*,SourceFile,LineNumberTable

# JNI 导出符号使用 NativeLib 的完整类名和 native 方法名进行绑定。
-keepclasseswithmembers class com.shanshui.performance.NativeLib {
    native <methods>;
}

# 网络 DTO 的 JSON 字段名属于服务端协议，不允许 R8 重命名。
-keepclassmembers class com.shanshui.performance.network.** {
    <fields>;
}

# Gson 持久化记录的字段名属于本地队列格式，进程重启后仍需按原字段恢复。
-keepclassmembers class com.shanshui.performance.crash.CrashQueueRecord {
    <fields>;
}
-keepclassmembers class com.shanshui.performance.crash.FileCrashQueue$DeadLetterRecord {
    <fields>;
}
-keepclassmembers class com.shanshui.performance.crash.CrashUploader$HttpErrorPayload {
    <fields>;
}
-keepclassmembers class com.shanshui.performance.fps.FpsAggregateRecord {
    <fields>;
}
-keepclassmembers class com.shanshui.performance.fps.FpsQueuedRecord {
    <fields>;
}
-keepclassmembers class com.shanshui.performance.fps.FpsCurrentFile {
    <fields>;
}
-keepclassmembers class com.shanshui.performance.jank.JankUploadRecord {
    <fields>;
}
-keepclassmembers class com.shanshui.performance.jank.JankArtifactUploader$ErrorPayload {
    <fields>;
}
-keepclassmembers class com.shanshui.performance.memory.MemoryQueuedRecord {
    <fields>;
}
-keepclassmembers class com.shanshui.performance.memory.leak.MemoryLeakReportQueuedRecord {
    <fields>;
}
