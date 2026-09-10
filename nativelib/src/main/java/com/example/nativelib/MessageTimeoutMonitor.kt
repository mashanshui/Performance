package com.example.nativelib

import android.os.Looper
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Kotlin实现的消息超时监控（ANR时间对齐方案）
 * 核心：时间对齐 + 常驻监控协程 + 原子变量保证线程安全
 */
class MessageTimeoutMonitor private constructor() : LooperMonitor.LooperListener {
    // 超时时长（毫秒，模拟系统ANR的5秒阈值）
    private val timeoutThreshold = 5000L

    // 目标超时时间（原子变量，多线程可见性）
    private val targetTimeoutTime = AtomicLong(0)

    // 当前消息开始时间（0表示无消息执行）
    private val currentMessageStartTime = AtomicLong(0)

    // 监控协程的作用域（用于控制生命周期）
    private val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 监控协程的延迟任务（用于动态调整检查时机）
    private var checkTimeoutJob: Job? = null

    // 单例模式
    companion object {
        val INSTANCE: MessageTimeoutMonitor by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
            MessageTimeoutMonitor().apply {
                // 初始化：启动常驻监控逻辑
                startMonitorLoop()
            }
        }
    }

    /**
     * 启动常驻的监控循环（核心逻辑）
     */
    private fun startMonitorLoop() {
        monitorScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val targetTime = targetTimeoutTime.get()
                val startTime = currentMessageStartTime.get()

                // 无消息执行时，短暂休眠后继续检查
                if (startTime == 0L) {
                    delay(1000)
                    continue
                }

                println("【超时检查】当前时间：$now，目标超时时间：$targetTime")

                when {
                    // 1. 当前时间 ≥ 目标超时时间 → 消息超时
                    now >= targetTime -> {
                        println("===== 检测到消息超时！开始抓取主线程堆栈 =====")
                        // 抓取主线程（UI线程）堆栈
                        captureMainThreadStack()
                        // 更新目标时间，继续监控（防止消息一直卡死后不再检查）
                        targetTimeoutTime.set(now + timeoutThreshold)
                        println("===== 堆栈抓取完成，更新下一次监控时间：${targetTimeoutTime.get()} =====")
                    }

                    // 2. 当前时间 < 目标超时时间 → 对齐目标时间，调整检查时机
                    else -> {
                        val delayTime = targetTime - now
                        println("【超时检查】消息未超时，下次检查延迟：${delayTime}ms（对齐目标时间）")
                        // 延迟到目标时间点再检查（精准对齐，减少无效轮询）
                        delay(if (delayTime > 0) delayTime else 1000)
                        continue
                    }
                }

                // 每次检查后短暂休眠，避免极端情况的空循环
                delay(1000)
            }
        }
    }

    /**
     * 消息开始执行时的钩子方法
     */
    override fun onMessageBegin(log: String, beginNs: Long) {
        val now = System.currentTimeMillis()
        currentMessageStartTime.set(now)
        // 计算目标超时时间 = 开始时间 + 超时时长
        val newTargetTime = now + timeoutThreshold
        targetTimeoutTime.set(newTargetTime)
        println("【消息开始】开始时间：$now，目标超时时间：$newTargetTime")
    }

    /**
     * 消息执行完毕时的钩子方法
     */
    override fun onMessageEnd(log: String, beginNs: Long, endNs: Long) {
        val now = System.currentTimeMillis()
        // 仅更新目标超时时间（无需取消监控，由监控循环自动识别）
        targetTimeoutTime.set(now + timeoutThreshold)
        currentMessageStartTime.set(0) // 重置开始时间
        println("【消息结束】结束时间：$now，更新后目标超时时间：${targetTimeoutTime.get()}")
    }

    /**
     * 抓取主线程（Android Looper主线程）堆栈
     */
    private fun captureMainThreadStack() {
        val mainThread = Looper.getMainLooper().thread
        mainThread.let { thread ->
            println("主线程名称：${thread.name}，状态：${thread.state}")
            println("主线程堆栈：")
            thread.stackTrace.forEachIndexed { index, element ->
                println("\t$index: $element")
            }
        }
    }

    /**
     * 销毁监控（释放资源）
     */
    fun destroy() {
        monitorScope.cancel()
        checkTimeoutJob?.cancel()
        currentMessageStartTime.set(0)
        targetTimeoutTime.set(0)
        println("【监控销毁】超时监控已停止")
    }
}
