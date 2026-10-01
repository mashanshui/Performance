package com.shanshui.performance

/** 受控 JVM 崩溃样本；调用方必须让异常进入 SDK 的默认未捕获异常处理器。 */
internal object CrashTestCases {
    /** 制造明确的主动抛出异常，作为已有测试按钮的独立页面版本。 */
    fun explicitException(): Nothing {
        throw IllegalStateException("Crash test: explicit failure")
    }

    /** 对空对象解引用，提供明确的空指针根因。 */
    fun nullPointer(): Int {
        // 故意保持为空，模拟业务对象未初始化。
        val value: String? = null
        return value!!.length
    }

    /** 访问数组范围之外的位置，提供明确的索引与容量不一致根因。 */
    fun arrayBounds(): Int {
        // 数组只有两个元素，第三个元素不存在。
        val values = intArrayOf(10, 20)
        return values[2]
    }

    /** 多种运行时状态走同一失败分支；异常不包含实际状态。 */
    fun runtimeState(state: String) {
        if (state != "ready") {
            throw IllegalStateException("Crash test: runtime state unavailable")
        }
    }

    /** 模拟异步任务处理外部响应；异常不携带响应内容或上游请求来源。 */
    fun externalResponse(response: String) {
        if (response != "accepted") {
            throw IllegalArgumentException("Crash test: external response rejected")
        }
    }

    /** 模拟持久化计数字段损坏；保留原始解析异常作为 cause，验证异常链定位。 */
    fun wrappedNumber(): Int {
        // 固定的无效测试数据，不读取真实用户文件或服务响应。
        val storedCount = "not-a-number"
        try {
            return storedCount.toInt()
        } catch (error: NumberFormatException) {
            throw IllegalStateException("Unable to load item count", error)
        }
    }
}
