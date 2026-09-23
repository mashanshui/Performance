# Leak report JSON 与队列元数据字段属于协议和本地持久格式。
-keepattributes Signature,*Annotation*,SourceFile,LineNumberTable
-keepclassmembers class com.shanshui.performance.network.** {
    <fields>;
}

# KOOM 通过 MonitorManager.addMonitorConfig 反射读取 MonitorConfig 的参数化父类。
# 必须保留泛型基类、配置实现和监控实例，否则 Release R8 会使泛型签名退化为原始类型。
-keep class com.kwai.koom.base.MonitorConfig { *; }
-keep class com.kwai.koom.javaoom.monitor.OOMMonitorConfig { *; }
-keep class com.kwai.koom.javaoom.monitor.OOMMonitor { *; }
