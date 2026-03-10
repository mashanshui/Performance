package com.example.nativelib

import android.os.Build
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

data class MessageInfo(val type: Int, val message: String, val wallTime: Long, val cpuTime: Long, val count: Int)

/**
 * @author mashanshui
 * @since 2026/2/5
 */
object MessageCollect : LooperMonitor.LooperListener {
    private const val SINGLE_MESSAGE_TIME_THRESHOLD = 300
    private const val MESSAGE_IDLE_TIME_THRESHOLD = 100
    private const val HISTORY_QUEUE_MAX_SIZE = 200
    private const val MESSAGE_TYPE_MULTIPLE = 0
    private const val MESSAGE_TYPE_SINGLE = 1
    private const val MESSAGE_TYPE_IDLE = 2
    private const val MESSAGE_TYPE_CURRENT = 3
    private const val MESSAGE_TYPE_PENDING = 4

    private var mSumMessageCount = 0
    private var mSumMessageTime = 0L
    private var mCurrentMessageStartTime = 0L
    private var mLastMessageEndTime = 0L
    private val mHistoryMessageQueue = ConcurrentLinkedQueue<MessageInfo>()
    private var mLatestMsgLog = ""
    private var mIsHasBegin = false

    fun start() {
        LooperMonitor.sMainMonitor.register(this)
    }

    override fun onMessageBegin(log: String) {
        mIsHasBegin = true
        mCurrentMessageStartTime = System.currentTimeMillis()
        mSumMessageCount++
        mLatestMsgLog = log
        if (mLastMessageEndTime != 0L) {
            val idleTime = System.currentTimeMillis() - mLastMessageEndTime
            if (idleTime > MESSAGE_IDLE_TIME_THRESHOLD) {
                enqueueHistoryMQ(MessageInfo(MESSAGE_TYPE_IDLE, "", idleTime, 0, 0))
            }
        }
    }

    override fun onMessageEnd(log: String) {
        if (!mIsHasBegin) {
            return
        }
        val messageCostTime = System.currentTimeMillis() - mCurrentMessageStartTime
        mSumMessageTime += messageCostTime
        if (messageCostTime > SINGLE_MESSAGE_TIME_THRESHOLD) {
            enqueueHistoryMQ(MessageInfo(MESSAGE_TYPE_MULTIPLE, log, mSumMessageTime, 0, mSumMessageCount))
            enqueueHistoryMQ(MessageInfo(MESSAGE_TYPE_SINGLE, log, messageCostTime, 0, 1))
            mSumMessageCount = 0
            mSumMessageTime = 0
        } else if (mSumMessageTime >= SINGLE_MESSAGE_TIME_THRESHOLD) {
            enqueueHistoryMQ(MessageInfo(MESSAGE_TYPE_MULTIPLE, log, mSumMessageTime, 0, mSumMessageCount))
            mSumMessageCount = 0
            mSumMessageTime = 0
        }
        mLastMessageEndTime = System.currentTimeMillis()
        mIsHasBegin = false
    }

    private fun enqueueHistoryMQ(m: MessageInfo) {
        if (mHistoryMessageQueue.size == HISTORY_QUEUE_MAX_SIZE) {
            mHistoryMessageQueue.poll()
        }
        mHistoryMessageQueue.offer(m)
    }

    fun getHistoryMessageQueue(): Queue<MessageInfo> {
        if (mIsHasBegin) {
            enqueueHistoryMQ(
                MessageInfo(
                    MESSAGE_TYPE_CURRENT, mLatestMsgLog,
                    System.currentTimeMillis() - mCurrentMessageStartTime, 0, 1
                )
            )
        }
        collectPendIngMessage()
        return mHistoryMessageQueue
    }

    private fun collectPendIngMessage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val mainQueue = Looper.getMainLooper().queue
            val field = mainQueue.javaClass.getDeclaredField("mMessages")
            field.isAccessible = true
            var mMessage = field.get(mainQueue) as Message?
            while (mMessage != null) {
                mHistoryMessageQueue.offer(
                    MessageInfo(
                        MESSAGE_TYPE_PENDING, mMessage.toString(),
                        0, 0, 1
                    )
                )
                val messageField = mMessage.javaClass.getDeclaredField("next")
                messageField.isAccessible = true
                mMessage = messageField.get(mMessage) as Message?
            }
        }
    }

    fun destroy() {
        LooperMonitor.sMainMonitor.unregister(this)
    }
}