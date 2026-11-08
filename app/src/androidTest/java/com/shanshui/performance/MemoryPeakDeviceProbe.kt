package com.shanshui.performance

import java.io.File
import java.util.UUID

/** 测试专用反射桥：直接执行 metrics 内部实现，不增加公开 SDK API。 */
internal class MemoryPeakDeviceProbe(
    /** 每个实验独立目录，绝不读取真实 Reporter 数据。 */ private val root: File,
) {
    /** 真实 current 成功替换次数。 */ var writes: Int = 0
        private set
    /** 累计成功替换的 JSON 字节数，非文件系统物理写放大。 */ var bytesWritten: Long = 0
        private set
    /** 内部类型不通过业务模块公开。 */ private val storeType = Class.forName("com.shanshui.performance.memory.MemoryEventStore")
    /** 完整事件类型。 */ private val eventType = Class.forName("com.shanshui.performance.network.MemoryMetricEvent")
    /** 原始读数类型。 */ private val valuesType = Class.forName("com.shanshui.performance.memory.MemorySampleValues")
    /** 可靠替换操作。 */ private val atomicType = Class.forName("com.shanshui.performance.memory.MemoryAtomicFile")
    /** 真实采样器。 */ private val samplerType = Class.forName("com.shanshui.performance.memory.MemorySampler")
    /** 原始读取器实例，使用生产默认 API。 */ private val sampler = samplerType.getDeclaredConstructor().newInstance()
    /** 测试使用同一个 Gson 默认协议配置。 */ private val gsonType = Class.forName("com.google.gson.Gson")
    /** 反序列化安全测试信封。 */ private val gson = gsonType.getDeclaredConstructor().newInstance()
    /** 固定本次实验身份。 */ private val processId = UUID.randomUUID().toString()
    /** 首次有效采样固定的 ID。 */ private val eventId = UUID.randomUUID().toString()
    /** 采集起点。 */ private val start = System.currentTimeMillis()
    /** 原子文件成功提交统计注入。 */ private val atomic = atomicType.constructors.first { it.parameterCount == 1 }.newInstance({ stage: String ->
        if (stage == "commit") { writes++; bytesWritten += File(root, "current.json").length() }
        Unit
    })
    /** 存储实例，全部日志禁用避免测量被逐样本日志影响。 */ private val store = storeType.constructors.first { it.parameterCount == 9 }.newInstance(
        root, 20L * 1024 * 1024, 90L * 86_400_000, 10, { _: () -> String -> Unit }, gson,
        { UUID.randomUUID().toString() }, { System.currentTimeMillis() }, atomic,
    )
    /** 后台运行状态机类型。 */ private val recorderType = Class.forName("com.shanshui.performance.memory.MemoryRunRecorder")
    /** 和 Reporter 相同的生产累计及持久化状态机。 */ private val recorder = recorderType.constructors.single().newInstance(store, processId, { createEvent() })

    /** 执行真实采样、比较及新高立即写盘；必要时包含周期检查点。 */
    fun sample(foreground: Boolean, checkpoint: Boolean = false) {
        /** 使用生产读取器产生的合法字节值，不在桥内另实现采样逻辑。 */
        val values = checkNotNull(samplerType.getMethod("sample").invoke(sampler))
        observe(values, foreground)
        if (checkpoint) checkpoint()
    }

    /** 合成确定读数仅验证新高与非新高路径，不能当作频率性能实验数据。 */
    fun synthetic(pss: Long, foreground: Boolean) {
        /** 仅用于确定分支的三项输入，不混入长周期测量。 */
        val values = valuesType.constructors.single().newInstance(pss, null, null)
        observe(values, foreground)
    }

    /** 执行周期检查点，不添加事件。 */
    fun checkpoint() { recorderType.getMethod("checkpoint").invoke(recorder) }

    /** 获取生产快照以验证写盘内容。 */
    fun snapshot(): String = File(root, "current.json").readText()

    /** 主线程测试仅调度 probe 调用，所有读取/IO 均由仪器工作线程执行。 */
    private fun observe(values: Any, foreground: Boolean) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        recorderType.getMethod("observe", valuesType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
            .invoke(recorder, values, System.currentTimeMillis(), foreground, if (foreground) "benchmark.Activity" else null)
    }

    /** 测试信封无 App Key、设备隐私或真实版本归属。 */
    private fun createEvent(): Any {
        /** 只含合成元数据的完整原运行信封。 */
        val json = """{"schemaVersion":2,"eventId":"$eventId","eventType":"memory_peak_summary","occurredAt":$start,
            "sessionId":"$processId","processId":"$processId","anonymousDeviceId":"benchmark","packageName":"benchmark",
            "appVersion":"benchmark","versionCode":1,"buildId":"benchmark","environment":"test","channel":"test",
            "osVersion":"${android.os.Build.VERSION.SDK_INT}","deviceModel":"emulator",
            "memoryPeakSummary":{"processName":"benchmark","collectionStartedAt":$start,"lastSampledAt":$start}}"""
        return checkNotNull(gsonType.getMethod("fromJson", String::class.java, Class::class.java).invoke(gson, json, eventType))
    }
}
