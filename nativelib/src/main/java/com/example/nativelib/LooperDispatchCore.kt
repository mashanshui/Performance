package com.example.nativelib

import java.util.ArrayDeque
import java.util.Collections
import com.example.nativelib.LooperMonitor.LooperListener
import com.example.nativelib.LooperMonitor.MessageRecord
import com.example.nativelib.LooperMonitor.RecentSnapshot
import com.example.nativelib.LooperMonitor.RecordingConfig

/** 与 Android 分离的状态机；时钟可替换，用于验证消息与清空边界。 */
internal class LooperDispatchCore(private val clock: () -> Long, private val report: (Exception) -> Unit) {
    private class ListenerState(val listener: LooperListener, var beginNs: Long? = null)
    private data class Active(val log: String, val beginNs: Long, val historyEpoch: Long, val recentEpoch: Long)
    private val lock = Any()
    private val listeners = mutableListOf<ListenerState>()
    private var active: Active? = null
    private var config = RecordingConfig()
    private var historyEpoch = 0L
    private var recentEpoch = 0L
    private val history = ArrayDeque<MessageRecord>()
    private val recent = ArrayDeque<MessageRecord>()
    private var count = 0L
    private var duration = 0L
    @Volatile var isClosed = false
        private set
    val listenerCount: Int get() = synchronized(lock) { listeners.size }

    fun register(listener: LooperListener) = synchronized(lock) {
        check(!isClosed) { "LooperMonitor 已关闭" }
        if (listeners.none { it.listener === listener }) listeners.add(ListenerState(listener))
    }
    fun unregister(listener: LooperListener) = synchronized(lock) {
        listeners.removeAll { it.listener === listener }
        Unit
    }

    fun dispatch(log: String?) {
        val begin = when (log?.firstOrNull()) { '>' -> true; '<' -> false; else -> return }
        val now = clock()
        val message: Active
        val snapshot: List<ListenerState>
        synchronized(lock) {
            if (isClosed) return
            if (begin) {
                if (active != null) return
                message = Active(log!!, now, historyEpoch, recentEpoch)
                active = message
            } else {
                message = active ?: return
                active = null
                val record = MessageRecord(message.log, message.beginNs, now, (now - message.beginNs).coerceAtLeast(0))
                if (config.historyEnabled && message.historyEpoch == historyEpoch) append(history, record, config.historyCapacity)
                if (config.denseEnabled && message.recentEpoch == recentEpoch) {
                    append(recent, record, config.recentCapacity)
                    count++
                    duration += record.durationNs
                }
            }
            snapshot = listeners.toList()
        }
        for (state in snapshot) {
            if (!synchronized(lock) { !isClosed && listeners.contains(state) }) continue
            try {
                // 有效性判断和业务回调均在锁外；注销取消尚未开始的快照成员。
                if (begin && !state.listener.isValid()) continue
                val deliver = synchronized(lock) {
                    if (isClosed || !listeners.contains(state)) false
                    else if (begin) { state.beginNs = message.beginNs; true }
                    else if (state.beginNs == message.beginNs) { state.beginNs = null; true }
                    else false
                }
                if (deliver) {
                    if (begin) state.listener.onMessageBegin(log!!, message.beginNs)
                    else state.listener.onMessageEnd(log!!, message.beginNs, now)
                }
            } catch (e: Exception) { report(e) }
        }
    }

    fun configure(value: RecordingConfig) = synchronized(lock) {
        check(!isClosed) { "LooperMonitor 已关闭" }
        if (config != value) {
            config = value
            historyEpoch++
            history.clear()
            clearRecent()
        }
    }
    fun history(includeCurrent: Boolean): List<MessageRecord> = synchronized(lock) {
        val records = history.toMutableList()
        active?.takeIf { includeCurrent && config.historyEnabled && it.historyEpoch == historyEpoch }?.let {
            records.add(MessageRecord(it.log, it.beginNs, null, (clock() - it.beginNs).coerceAtLeast(0)))
        }
        immutable(records)
    }
    fun recent(): RecentSnapshot = synchronized(lock) { RecentSnapshot(immutable(recent.toList()), count, duration) }
    fun clearRecent() = synchronized(lock) {
        recentEpoch++
        recent.clear()
        count = 0
        duration = 0
    }
    fun close() = synchronized(lock) {
        isClosed = true
        listeners.clear()
        active = null
        history.clear()
        clearRecent()
    }
    /** Printer 丢失期间无法确认消息边界，恢复时丢弃未完成状态。 */
    fun resetDispatch() = synchronized(lock) {
        active = null
        listeners.forEach { it.beginNs = null }
    }
    private fun append(queue: ArrayDeque<MessageRecord>, record: MessageRecord, capacity: Int) {
        if (queue.size >= capacity) queue.removeFirst()
        queue.addLast(record)
    }
    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
