# Jank 队列与协议 DTO 字段名需要在宿主 R8 后保持稳定。
-keepattributes Signature,*Annotation*,SourceFile,LineNumberTable
-keepclassmembers class com.shanshui.performance.network.** {
    <fields>;
}
