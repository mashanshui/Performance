package com.example.nativelib

data class MessageInfo(val message: String, val messageTime: Long)
/**
 * @author mashanshui
 * @since 2026/2/5
 */
class MessageCollect : LooperMonitor.LooperListener {
    companion object{
        private const val SINGLE_MESSAGE_TIME_THRESHOLD = 300
    }
    private var mSumMessageCount = 0
    private var mSumMessageTime = 0L
    private var mCurrentMessageStartTime = 0L
    private val mMessageQueue = mutableListOf<MessageInfo>()

    init {
        LooperMonitor.sMainMonitor.register(this)
    }

    override fun onMessageBegin(log: String) {
        mSumMessageCount++
        mCurrentMessageStartTime = System.currentTimeMillis()
    }

    override fun onMessageEnd(log: String) {
        val messageCostTime = System.currentTimeMillis() - mCurrentMessageStartTime
        if (messageCostTime > SINGLE_MESSAGE_TIME_THRESHOLD) {
            mMessageQueue.add(MessageInfo(log, messageCostTime))
        }
    }

    fun destroy() {
        LooperMonitor.sMainMonitor.unregister(this)
    }
}