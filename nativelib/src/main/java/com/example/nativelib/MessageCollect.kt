package com.example.nativelib

import java.util.concurrent.ConcurrentLinkedQueue

data class MessageInfo(val type: Int, val message: String, val wallTime: Long, val cpuTime: Long, val count: Int)

/**
 * @author mashanshui
 * @since 2026/2/5
 */
class MessageCollect : LooperMonitor.LooperListener {
    companion object {
        private const val SINGLE_MESSAGE_TIME_THRESHOLD = 300
        private const val MESSAGE_IDLE_TIME_THRESHOLD = 100
        private const val HISTORY_QUEUE_MAX_SIZE = 200
        private const val MESSAGE_TYPE_MULTIPLE = 0
        private const val MESSAGE_TYPE_SINGLE = 1
        private const val MESSAGE_TYPE_IDLE = 2
    }

    private var mSumMessageCount = 0
    private var mSumMessageTime = 0L
    private var mCurrentMessageStartTime = 0L
    private var mLastMessageEndTime = 0L
    private val mHistoryMessageQueue = ConcurrentLinkedQueue<MessageInfo>()

    init {
        LooperMonitor.sMainMonitor.register(this)
    }

    override fun onMessageBegin(log: String) {
        mSumMessageCount++
        mCurrentMessageStartTime = System.currentTimeMillis()
        val idleTime = System.currentTimeMillis() - mLastMessageEndTime
        if (idleTime > MESSAGE_IDLE_TIME_THRESHOLD) {
            enqueueHistoryMQ(MessageInfo(MESSAGE_TYPE_IDLE, "", idleTime, 0, 0))
        }
    }

    override fun onMessageEnd(log: String) {
        val messageCostTime = System.currentTimeMillis() - mCurrentMessageStartTime
        mSumMessageTime += messageCostTime
        if (messageCostTime > SINGLE_MESSAGE_TIME_THRESHOLD) {
            enqueueHistoryMQ(MessageInfo(MESSAGE_TYPE_MULTIPLE, "", mSumMessageTime, 0, mSumMessageCount))
            enqueueHistoryMQ(MessageInfo(MESSAGE_TYPE_SINGLE, log, messageCostTime, 0, 1))
            mSumMessageCount = 0
            mSumMessageTime = 0
        } else if (mSumMessageTime >= SINGLE_MESSAGE_TIME_THRESHOLD) {
            enqueueHistoryMQ(MessageInfo(MESSAGE_TYPE_MULTIPLE, "", mSumMessageTime, 0, mSumMessageCount))
            mSumMessageCount = 0
            mSumMessageTime = 0
        }
        mLastMessageEndTime = System.currentTimeMillis()
    }

    private fun enqueueHistoryMQ(m: MessageInfo) {
        if (mHistoryMessageQueue.size == HISTORY_QUEUE_MAX_SIZE) {
            mHistoryMessageQueue.poll()
        }
        mHistoryMessageQueue.offer(m)
    }

    fun destroy() {
        LooperMonitor.sMainMonitor.unregister(this)
    }
}