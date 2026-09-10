package com.example.nativelib

import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import android.os.SystemClock
import android.util.Log
import android.util.Printer

/**
 * 借鉴 Tencent Matrix LooperMonitor 的 Printer 链与空闲检查设计，独立实现生命周期及快照。
 * 回调在目标线程同步执行，应避免磁盘、网络和重计算；本组件不负责上传。
 */
class LooperMonitor private constructor(val looper: Looper) : AutoCloseable {
    companion object {
        private val monitors = mutableMapOf<Looper, LooperMonitor>()
        val sMainMonitor: LooperMonitor get() = of(Looper.getMainLooper())

        /** 同一 Looper 共享实例；工作线程退出前应主动关闭。 */
        fun of(looper: Looper): LooperMonitor = synchronized(monitors) {
            monitors[looper]?.takeUnless { it.core.isClosed } ?: LooperMonitor(looper).also {
                monitors[looper] = it
            }
        }
    }

    interface LooperListener {
        /** 仅在开始时判断；已开始的通知在仍注册时总会收到配对结束。 */
        fun isValid(): Boolean = true
        fun onMessageBegin(log: String, beginNs: Long)
        fun onMessageEnd(log: String, beginNs: Long, endNs: Long)
    }

    data class RecordingConfig(
        val historyEnabled: Boolean = false,
        val denseEnabled: Boolean = false,
        val historyCapacity: Int = 200,
        val recentCapacity: Int = 5000,
    ) {
        init { require(historyCapacity > 0 && recentCapacity > 0) { "消息队列容量必须为正数" } }
    }

    /** endNs 为空表示仍执行；durationNs 是快照时观察到的耗时。 */
    data class MessageRecord(val log: String, val beginNs: Long, val endNs: Long?, val durationNs: Long)
    data class RecentSnapshot(val messages: List<MessageRecord>, val completedCount: Long, val durationNs: Long)
    enum class AttachmentState { PENDING, ATTACHED, REFLECTION_UNAVAILABLE, UNAVAILABLE, CLOSED }

    private val core = LooperDispatchCore(SystemClock::elapsedRealtimeNanos) {
        Log.e("LooperMonitor", "监听器执行失败", it)
    }
    private val handler = Handler(looper)
    private var queue: MessageQueue? = null
    private val hook = LooperPrinterHook(object : LooperPrinterAccess {
        override fun read(): Printer? = Looper::class.java.getDeclaredField("mLogging").run {
            isAccessible = true
            get(looper) as Printer?
        }
        override fun write(printer: Printer?) = looper.setMessageLogging(printer)
    }, core, SystemClock::uptimeMillis) {
        Log.e("LooperMonitor", "无法安全访问 Looper Printer，停止自动挂接", it)
    }
    private val idle = MessageQueue.IdleHandler { hook.onIdle(); !core.isClosed }
    val attachmentState: AttachmentState get() = hook.state
    val listenerCount: Int get() = core.listenerCount

    // 在目标线程安装，兼容 API 21 的 myQueue，并串行处理日志与挂接。
    private val install = Runnable {
        synchronized(hook) {
            if (!core.isClosed) {
                hook.install()
                if (hook.state == AttachmentState.ATTACHED) {
                    queue = Looper.myQueue().also { it.addIdleHandler(idle) }
                }
            }
        }
    }
    init {
        if (Looper.myLooper() === looper) install.run()
        else if (!handler.post(install)) hook.unavailable()
    }

    fun register(listener: LooperListener) = core.register(listener)
    fun unregister(listener: LooperListener) = core.unregister(listener)
    fun configureRecording(config: RecordingConfig) = core.configure(config)
    fun historySnapshot(includeCurrent: Boolean = true): List<MessageRecord> = core.history(includeCurrent)
    fun recentSnapshot(): RecentSnapshot = core.recent()
    fun clearRecentMessages() = core.clearRecent()

    /** 注销不补造结束通知，调用方应清理自己在回调中开启的外部资源。 */
    override fun close() {
        synchronized(hook) {
            core.close()
            handler.removeCallbacks(install)
            queue?.removeIdleHandler(idle)
            queue = null
            hook.close()
        }
        synchronized(monitors) {
            if (monitors[looper] === this) monitors.remove(looper)
        }
    }
}
