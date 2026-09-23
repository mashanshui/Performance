package com.shanshui.performance.memory.leak

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.shanshui.performance.memory.leak.MemoryLeakConfig
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** SDK 对泄漏检测资源的最小生命周期抽象，便于初始化回滚时替换测试实现。 */
internal interface MemoryLeakWatcher : Closeable {
    val isAvailable: Boolean
}

/**
 * Activity 销毁后的弱引用重检器。
 *
 * 生命周期回调只入队和调整前后台状态，GC、finalization 与泄漏回调全部在专用后台线程执行。
 * 默认回调由组件工厂注入 KOOM dump，检测器本身不依赖 Matrix 或 HPROF 解析器。
 */
internal class ActivityLeakWatcher(
    private val application: Application,
    private val config: MemoryLeakConfig,
    private val onLeakDetected: () -> Unit,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val isDebuggerConnected: () -> Boolean = { Debug.isDebuggerConnected() },
    private val gcAndFinalize: () -> Unit = {
        // 这两个调用必须位于 HandlerThread 的扫描任务中，不能阻塞生命周期回调线程。
        Runtime.getRuntime().gc()
        try {
            Thread.sleep(100L)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        Runtime.getRuntime().runFinalization()
    },
) : Application.ActivityLifecycleCallbacks, MemoryLeakWatcher {
    private val closed = AtomicBoolean(false)
    private val startedActivityCount = AtomicInteger(0)
    private val foreground = AtomicBoolean(isProcessForeground(application))
    private val callbackInvoked = AtomicBoolean(false)
    private val stateLock = Any()
    private val tracker = ActivityLeakTracker<Activity>(
        maxRecheckCount = config.maxRecheckCount,
        onLeakDetected = { handleLeakDetected() },
    )
    private val worker = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(worker.looper)
    private var scheduledScan: Runnable? = null
    private var lifecycleCallbacksRegistered = false

    init {
        try {
            application.registerActivityLifecycleCallbacks(this)
            lifecycleCallbacksRegistered = true
            Log.i(
                TAG,
                "initialized foregroundInterval=${config.foregroundScanIntervalMillis} " +
                    "backgroundInterval=${config.backgroundScanIntervalMillis} " +
                    "maxRecheck=${config.maxRecheckCount} " +
                    "skipDebugger=${config.skipWhenDebuggerConnected}",
            )
        } catch (throwable: Throwable) {
            Log.w(TAG, "unable to register activity lifecycle callbacks")
            closeWorker()
            throw throwable
        }
    }

    /** 检测器仍绑定宿主且后台线程尚未关闭时为 true。 */
    override val isAvailable: Boolean
        get() = !closed.get() && worker.isAlive

    /** 仅用于测试和诊断，不返回 Activity 强引用。 */
    internal fun pendingCandidateCount(): Int = tracker.size

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        if (closed.get()) return
        val count = startedActivityCount.incrementAndGet()
        if (count == 1) {
            foreground.set(true)
            tracker.reschedule(clock(), currentScanIntervalMillis())
            scheduleNextScan()
        }
    }

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        if (closed.get()) return
        val count = decrementStartedActivityCount()
        if (count == 0) {
            foreground.set(false)
            tracker.reschedule(clock(), currentScanIntervalMillis())
            scheduleNextScan()
        }
    }

    /** 在不依赖 API 24 AtomicInteger 扩展的情况下安全减少前台 Activity 计数。 */
    private fun decrementStartedActivityCount(): Int {
        while (true) {
            // 读取当前值并把计数下限固定为零，避免生命周期回调重复触发时出现负数。
            val currentCount = startedActivityCount.get()
            val nextCount = (currentCount - 1).coerceAtLeast(0)
            if (startedActivityCount.compareAndSet(currentCount, nextCount)) {
                return nextCount
            }
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (closed.get()) return
        try {
            // tracker 内部只保存此弱引用，不会因检测器队列延长 Activity 生命周期。
            if (tracker.enqueue(WeakReference(activity), clock(), config.gcDelayMillis)) {
                Log.d(TAG, "queued destroyed activity=${activity.javaClass.name}")
                scheduleNextScan()
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "unable to enqueue destroyed activity")
        }
    }

    /** 注销生命周期、清空弱引用、取消任务，并唤醒后安全停止 HandlerThread。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (lifecycleCallbacksRegistered) {
            runCatching { application.unregisterActivityLifecycleCallbacks(this) }
                .onFailure { Log.w(TAG, "unable to unregister activity lifecycle callbacks") }
            lifecycleCallbacksRegistered = false
        }
        synchronized(stateLock) {
            scheduledScan?.let { handler.removeCallbacks(it) }
            scheduledScan = null
            tracker.clear()
        }
        closeWorker()
    }

    private fun closeWorker() {
        runCatching { handler.removeCallbacksAndMessages(null) }
            .onFailure { Log.w(TAG, "unable to clear activity leak tasks") }
        runCatching { worker.quitSafely() }
            .onFailure { Log.w(TAG, "unable to stop activity leak thread") }
        if (Thread.currentThread() !== worker) {
            runCatching { worker.join(THREAD_JOIN_TIMEOUT_MILLIS) }
                .onFailure { Log.w(TAG, "unable to join activity leak thread") }
        }
    }

    private fun handleLeakDetected() {
        if (!callbackInvoked.compareAndSet(false, true) || closed.get()) return
        Log.w(TAG, "activity leak confirmed; requesting KOOM dump")
        runCatching { onLeakDetected() }
            .onFailure { Log.w(TAG, "activity leak callback failed") }
    }

    private fun scan() {
        if (closed.get()) return
        val interval = currentScanIntervalMillis()
        runCatching {
            val result = tracker.scan(
                nowMillis = clock(),
                nextCheckDelayMillis = interval,
                debuggerConnected = config.skipWhenDebuggerConnected && isDebuggerConnected(),
                gcAndFinalize = gcAndFinalize,
            )
            if (result.skippedByDebugger || result.checkedCount > 0 || result.collectedCount > 0) {
                Log.d(
                    TAG,
                    "scan checked=${result.checkedCount} collected=${result.collectedCount} " +
                        "leaked=${result.leakedCount} deferred=${result.deferredCount} " +
                        "skippedByDebugger=${result.skippedByDebugger}",
                )
            }
        }.onFailure { Log.w(TAG, "activity leak scan failed") }
        if (!closed.get()) scheduleNextScan()
    }

    private fun scheduleNextScan() {
        if (closed.get()) return
        val dueAtMillis = tracker.nextDueAtMillis() ?: return
        val delayMillis = (dueAtMillis - clock()).coerceAtLeast(0L)
        lateinit var task: Runnable
        task = Runnable {
            synchronized(stateLock) {
                if (scheduledScan === task) scheduledScan = null
            }
            scan()
        }
        synchronized(stateLock) {
            scheduledScan?.let { handler.removeCallbacks(it) }
            scheduledScan = task
        }
        runCatching { handler.postDelayed(task, delayMillis) }
            .onFailure {
                synchronized(stateLock) {
                    if (scheduledScan === task) scheduledScan = null
                }
                Log.w(TAG, "unable to schedule activity leak scan")
            }
    }

    private fun currentScanIntervalMillis(): Long {
        return if (foreground.get()) {
            config.foregroundScanIntervalMillis
        } else {
            config.backgroundScanIntervalMillis
        }
    }

    companion object {
        private const val TAG = "ActivityLeakWatcher"
        private const val THREAD_NAME = "activity-leak-watcher"
        private const val THREAD_JOIN_TIMEOUT_MILLIS = 2_000L
        private val MAIN_PROCESS_IMPORTANCE = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

        /** 与 KOOM DefaultInitTask 的 sdkVersionMatch 保持一致，只接受 API 21..36。 */
        internal fun isSupported(application: Application): Boolean {
            return Build.VERSION.SDK_INT in Build.VERSION_CODES.LOLLIPOP..36 &&
                isProcessAvailable(application)
        }

        private fun isProcessAvailable(application: Application): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return application.packageName == Application.getProcessName()
            }
            val processInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(processInfo)
            return application.packageName == processInfo.processName
        }

        private fun isProcessForeground(application: Application): Boolean {
            return runCatching {
                val processInfo = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(processInfo)
                processInfo.importance == MAIN_PROCESS_IMPORTANCE
            }.getOrDefault(true)
        }
    }
}

/**
 * 不依赖 Android Looper 的 Activity 泄漏重检状态机。
 *
 * 生产代码传入 [WeakReference<Activity>]，JVM 测试可以使用普通对象和手动 clear() 的弱引用，
 * 从而验证回收、重检次数、调试器跳过和回调去重，而不依赖真实 Android GC 时机。
 */
internal class ActivityLeakTracker<T : Any>(
    private val maxRecheckCount: Int,
    private val onLeakDetected: () -> Unit,
) {
    private data class Candidate<T : Any>(
        val reference: WeakReference<T>,
        var nextCheckAtMillis: Long,
        var recheckCount: Int,
    )

    private val lock = Any()
    private val candidates = ArrayList<Candidate<T>>()

    val size: Int
        get() = synchronized(lock) { candidates.size }

    /** 入队时只保存弱引用；同一仍存活对象的重复生命周期通知不会重复入队。 */
    fun enqueue(reference: WeakReference<T>, nowMillis: Long, delayMillis: Long): Boolean {
        val target = reference.get() ?: return false
        return synchronized(lock) {
            if (candidates.any { it.reference.get() === target }) {
                false
            } else {
                candidates += Candidate(
                    reference = reference,
                    nextCheckAtMillis = safeAdd(nowMillis, delayMillis),
                    recheckCount = 0,
                )
                true
            }
        }
    }

    /**
     * 扫描到期候选；调试器跳过时既不触发 GC，也不触发回调，只推迟下一次检查。
     * 候选在回调前先移除，因此即使回调抛异常也不会再次 dump。
     */
    fun scan(
        nowMillis: Long,
        nextCheckDelayMillis: Long,
        debuggerConnected: Boolean,
        gcAndFinalize: () -> Unit,
    ): ScanResult {
        if (debuggerConnected) {
            val deferredCount = synchronized(lock) {
                candidates.count { candidate ->
                    if (candidate.nextCheckAtMillis <= nowMillis) {
                        candidate.nextCheckAtMillis = safeAdd(nowMillis, nextCheckDelayMillis)
                        true
                    } else {
                        false
                    }
                }
            }
            return ScanResult(
                checkedCount = 0,
                collectedCount = 0,
                leakedCount = 0,
                deferredCount = deferredCount,
                skippedByDebugger = true,
            )
        }

        val hasDueCandidate = synchronized(lock) {
            candidates.any { it.nextCheckAtMillis <= nowMillis }
        }
        if (!hasDueCandidate) {
            return ScanResult.EMPTY
        }

        // 由调用方保证此动作位于专用后台线程；状态机本身不触碰 Android API。
        gcAndFinalize()

        var checkedCount = 0
        var collectedCount = 0
        val leakedCandidates = ArrayList<T>()
        synchronized(lock) {
            val iterator = candidates.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.nextCheckAtMillis > nowMillis) continue
                val target = candidate.reference.get()
                if (target == null) {
                    iterator.remove()
                    collectedCount++
                    continue
                }
                checkedCount++
                candidate.recheckCount++
                if (candidate.recheckCount >= maxRecheckCount) {
                    iterator.remove()
                    leakedCandidates += target
                } else {
                    candidate.nextCheckAtMillis = safeAdd(nowMillis, nextCheckDelayMillis)
                }
            }
        }
        leakedCandidates.forEach {
            // 调用方可在此处做一次性保护；tracker 先移除候选再通知。
            runCatching { onLeakDetected() }
        }
        return ScanResult(
            checkedCount = checkedCount,
            collectedCount = collectedCount,
            leakedCount = leakedCandidates.size,
            deferredCount = 0,
            skippedByDebugger = false,
        )
    }

    fun nextDueAtMillis(): Long? = synchronized(lock) {
        candidates.minOfOrNull { it.nextCheckAtMillis }
    }

    /** 前后台切换时将已有候选移动到当前状态对应的扫描周期。 */
    fun reschedule(nowMillis: Long, nextCheckDelayMillis: Long) = synchronized(lock) {
        candidates.forEach { candidate ->
            candidate.nextCheckAtMillis = safeAdd(nowMillis, nextCheckDelayMillis)
        }
    }

    fun clear() = synchronized(lock) { candidates.clear() }

    data class ScanResult(
        val checkedCount: Int,
        val collectedCount: Int,
        val leakedCount: Int,
        val deferredCount: Int,
        val skippedByDebugger: Boolean,
    ) {
        companion object {
            val EMPTY = ScanResult(0, 0, 0, 0, false)
        }
    }

    private companion object {
        fun safeAdd(first: Long, second: Long): Long {
            if (second <= 0L) return first
            return if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
        }
    }
}
