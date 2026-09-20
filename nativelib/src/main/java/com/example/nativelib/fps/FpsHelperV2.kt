package com.example.nativelib.fps

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi
import com.example.nativelib.FpsLogLevel
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 一个页面在某个刷新率桶内的 FPS 统计快照。
 *
 * [activeDurationNs] 只包含收到并通过校验的真实 UI 刷新帧，因此不会把页面静止
 * 时间计入分母。该类型同时被页面采集器和运行内聚合器使用。
 */
data class FpsWindowSummary(
    val scene: String,
    val algorithmVersion: String,
    val refreshRateHz: Double,
    val activeDurationNs: Long,
    val uiRefreshFrameCount: Long,
    val totalFrameDurationNs: Long,
    val maxFrameDurationNs: Long,
    val callbackDropCount: Long,
    val firstSampleAtMillis: Long,
    val lastSampleAtMillis: Long,
) {
    /** 将纳秒活跃时长按向上取整转换成服务端要求的毫秒整数。 */
    val activeDurationMs: Long
        get() = ceil(activeDurationNs / NANOS_PER_MILLISECOND.toDouble()).toLong()

    /** 返回未归一化的观测 FPS；没有有效帧时返回 null。 */
    val rawFps: Double?
        get() = if (activeDurationNs > 0 && uiRefreshFrameCount > 0) {
            uiRefreshFrameCount * NANOS_PER_SECOND.toDouble() / activeDurationNs
        } else {
            null
        }

    /** 按 fps-v1 公式将当前刷新率归一化到 60 Hz。 */
    val normalizedFps60: Double?
        get() = rawFps?.let { fps ->
            min(60.0, fps * 60.0 / refreshRateHz.coerceAtLeast(MIN_REFRESH_RATE_HZ))
        }

    /** 返回该统计区间的平均有效帧耗时，便于摘要日志展示。 */
    val averageFrameDurationNs: Long
        get() = if (uiRefreshFrameCount > 0) {
            totalFrameDurationNs / uiRefreshFrameCount
        } else {
            0L
        }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MIN_REFRESH_RATE_HZ = 1.0
    }
}

/**
 * 纯 Kotlin 帧累加器，负责 fps-v1 的纳秒级计算。
 *
 * 该类不依赖 Android API，便于覆盖 60/90/120 Hz、慢帧、刷新率切换和静止区间。
 */
internal class FpsFrameAccumulator(
    private val scene: String,
    private val algorithmVersion: String = FPS_ALGORITHM_VERSION,
    private val refreshRateHz: Double,
) {
    private var activeDurationNs: Long = 0L
    private var uiRefreshFrameCount: Long = 0L
    private var totalFrameDurationNs: Long = 0L
    private var maxFrameDurationNs: Long = 0L
    private var callbackDropCount: Long = 0L
    private var firstSampleAtMillis: Long = 0L
    private var lastSampleAtMillis: Long = 0L

    /** 记录回调间隔内丢失的回调数量，但不把它伪造成 UI 掉帧。 */
    fun addCallbackDrops(count: Int) {
        callbackDropCount = saturatingAdd(callbackDropCount, count.coerceAtLeast(0).toLong())
    }

    /**
     * 记录一帧有效渲染耗时。
     *
     * 有效耗时至少按当前刷新率的帧预算计算，保证满速 90/120 Hz 归一化后仍为 60。
     */
    fun addFrame(totalDurationNs: Long, sampledAtMillis: Long): Boolean {
        if (totalDurationNs <= 0L || refreshRateHz <= 0.0) {
            return false
        }
        val frameBudgetNs = (NANOS_PER_SECOND / refreshRateHz).toLong().coerceAtLeast(1L)
        val effectiveDurationNs = max(frameBudgetNs, totalDurationNs)
        val wasEmpty = uiRefreshFrameCount == 0L
        activeDurationNs = saturatingAdd(activeDurationNs, effectiveDurationNs)
        totalFrameDurationNs = saturatingAdd(totalFrameDurationNs, effectiveDurationNs)
        uiRefreshFrameCount = saturatingAdd(uiRefreshFrameCount, 1L)
        maxFrameDurationNs = max(maxFrameDurationNs, effectiveDurationNs)
        if (wasEmpty) {
            firstSampleAtMillis = sampledAtMillis
        }
        lastSampleAtMillis = sampledAtMillis
        return true
    }

    /** 判断当前桶是否至少包含一帧有效 UI 刷新。 */
    fun hasFrames(): Boolean = uiRefreshFrameCount > 0

    /** 将当前桶转成快照；空桶返回 null，避免服务端收到零帧记录。 */
    fun snapshot(): FpsWindowSummary? {
        if (!hasFrames()) {
            return null
        }
        return FpsWindowSummary(
            scene = scene,
            algorithmVersion = algorithmVersion,
            refreshRateHz = refreshRateHz,
            activeDurationNs = activeDurationNs,
            uiRefreshFrameCount = uiRefreshFrameCount,
            totalFrameDurationNs = totalFrameDurationNs,
            maxFrameDurationNs = maxFrameDurationNs,
            callbackDropCount = callbackDropCount,
            firstSampleAtMillis = firstSampleAtMillis,
            lastSampleAtMillis = lastSampleAtMillis,
        )
    }

    /** 返回快照并清空当前桶，供每秒摘要日志使用。 */
    fun snapshotAndReset(): FpsWindowSummary? {
        val result = snapshot()
        reset()
        return result
    }

    /** 清空累加器但保留其场景、算法和刷新率桶身份。 */
    fun reset() {
        activeDurationNs = 0L
        uiRefreshFrameCount = 0L
        totalFrameDurationNs = 0L
        maxFrameDurationNs = 0L
        callbackDropCount = 0L
        firstSampleAtMillis = 0L
        lastSampleAtMillis = 0L
    }

    /** 在 Long 溢出时饱和到最大值，避免极端长会话产生负数指标。 */
    private fun saturatingAdd(first: Long, second: Long): Long {
        if (second <= 0L) {
            return first
        }
        return if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second
    }

    private companion object {
        const val FPS_ALGORITHM_VERSION = "fps-v1"
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/**
 * 基于 Window.FrameMetrics 的页面帧率采集器。
 *
 * FrameMetrics 回调在专用 HandlerThread 上处理，页面线程只负责注册/移除监听；
 * 采集器不会在回调中执行磁盘或网络操作。调用 [close] 后不可再次启动。
 */
class FpsHelperV2(
    private val logLevel: FpsLogLevel = FpsLogLevel.OFF,
    private var summaryListener: ((FpsWindowSummary) -> Unit)? = null,
    private val wallClockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var window: Window? = null
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null
    private var logRunnable: Runnable? = null
    private var generation: Long = 0L
    private var running: Boolean = false
    private var closed: Boolean = false
    private var scene: String = DEFAULT_SCENE
    private var refreshRateHz: Double = 0.0
    private var sceneAccumulator: FpsFrameAccumulator? = null
    private var intervalAccumulator: FpsFrameAccumulator? = null

    /** 设置页面统计快照回调；回调只应做内存合并，不应执行磁盘或网络操作。 */
    fun setSummaryListener(listener: ((FpsWindowSummary) -> Unit)?) {
        synchronized(lock) {
            summaryListener = listener
        }
    }

    /** 返回当前采集器是否正在监听 Window。 */
    fun isRunning(): Boolean = synchronized(lock) { running }

    /**
     * 开始监听页面的真实 FrameMetrics。
     *
     * 方法可从主线程调用；FrameMetrics 回调和摘要定时任务在采集线程执行。
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun start(targetWindow: Window, sceneName: String = DEFAULT_SCENE) {
        validateScene(sceneName)
        synchronized(lock) {
            check(!closed) { "FpsHelperV2 is already closed" }
            if (running) {
                logSummary { "start ignored: already running scene=$scene" }
                return
            }
            if (!targetWindow.decorView.isHardwareAccelerated) {
                logSummary { "start skipped: window is not hardware accelerated" }
                return
            }
            ensureHandlerLocked()
            generation += 1L
            val callbackGeneration = generation
            window = targetWindow
            scene = sceneName
            refreshRateHz = 0.0
            sceneAccumulator = null
            intervalAccumulator = null
            running = true
            val listener = createFrameListenerLocked(callbackGeneration)
            frameListener = listener
            runCatching {
                targetWindow.addOnFrameMetricsAvailableListener(listener, handler)
            }.onFailure { throwable ->
                // 注册失败时立即回滚 running 状态，下一次 onResume 可以重新尝试。
                running = false
                generation += 1L
                window = null
                frameListener = null
                sceneAccumulator = null
                intervalAccumulator = null
                refreshRateHz = 0.0
                logSummary {
                    "start failed while registering FrameMetrics listener " +
                        "type=${throwable.javaClass.simpleName}"
                }
            }.getOrThrow()
            scheduleSummaryLogLocked(callbackGeneration)
            logSummary { "start accepted scene=$sceneName generation=$callbackGeneration" }
        }
    }

    /** 兼容旧调用方的入口；旧参数只代表滚动过滤，现版本统一采集页面真实刷新帧。 */
    @RequiresApi(Build.VERSION_CODES.N)
    fun start(targetWindow: Window, isOnlyCollectScroll: Boolean) {
        logSummary { "legacy start flag ignored isOnlyCollectScroll=$isOnlyCollectScroll" }
        start(targetWindow, DEFAULT_SCENE)
    }

    /** 更新场景名并结算旧场景当前刷新率桶。 */
    fun setScene(sceneName: String) {
        validateScene(sceneName)
        var completed: FpsWindowSummary? = null
        synchronized(lock) {
            if (scene == sceneName) {
                logSummary { "scene change ignored: same scene=$sceneName" }
                return
            }
            val previousScene = scene
            scene = sceneName
            if (running) {
                completed = sceneAccumulator?.snapshot()
                val activeWindow = window
                val oldListener = frameListener
                frameListener = null
                if (activeWindow != null && oldListener != null) {
                    runCatching { activeWindow.removeOnFrameMetricsAvailableListener(oldListener) }
                        .onFailure {
                            logSummary {
                                "scene change old listener removal failed " +
                                    "type=${it.javaClass.simpleName}"
                            }
                        }
                }
                // 场景切换提升采集代次，切断已经排队但属于旧场景的回调。
                generation += 1L
                val callbackGeneration = generation
                logRunnable?.let { runnable -> handler?.removeCallbacks(runnable) }
                logRunnable = null
                sceneAccumulator = if (refreshRateHz > 0.0) {
                    FpsFrameAccumulator(sceneName, refreshRateHz = refreshRateHz)
                } else {
                    null
                }
                intervalAccumulator = if (refreshRateHz > 0.0) {
                    FpsFrameAccumulator(sceneName, refreshRateHz = refreshRateHz)
                } else {
                    null
                }
                if (activeWindow != null) {
                    val listener = createFrameListenerLocked(callbackGeneration)
                    runCatching {
                        activeWindow.addOnFrameMetricsAvailableListener(listener, handler)
                        frameListener = listener
                    }.onFailure { throwable ->
                        running = false
                        window = null
                        sceneAccumulator = null
                        intervalAccumulator = null
                        refreshRateHz = 0.0
                        logSummary {
                            "scene change listener registration failed " +
                                "type=${throwable.javaClass.simpleName}"
                        }
                    }
                }
                if (running) {
                    scheduleSummaryLogLocked(callbackGeneration)
                }
            }
            logSummary { "scene changed from=$previousScene to=$sceneName; old bucket finalized" }
        }
        completed?.let(::emitSummary)
    }

    /** 创建绑定采集代次的 FrameMetrics 监听器；旧代次回调会被忽略。 */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun createFrameListenerLocked(
        callbackGeneration: Long,
    ): Window.OnFrameMetricsAvailableListener {
        return object : Window.OnFrameMetricsAvailableListener {
            /** 在采集 HandlerThread 上接收并处理单帧 FrameMetrics。 */
            override fun onFrameMetricsAvailable(
                callbackWindow: Window,
                frameMetrics: FrameMetrics,
                dropCountSinceLastInvocation: Int,
            ) {
                onFrameMetrics(
                    callbackGeneration,
                    callbackWindow,
                    frameMetrics,
                    dropCountSinceLastInvocation,
                )
            }
        }
    }

    /** 停止监听页面，返回并分发当前刷新率桶，停止后不再打印摘要日志。 */
    fun stop() {
        val completed = synchronized(lock) { stopLocked() }
        completed?.let(::emitSummary)
    }

    /** 释放 HandlerThread、Window 引用和监听器；释放后不可再次启动。 */
    fun close() {
        val completed = synchronized(lock) {
            if (closed) {
                logSummary { "close ignored: already closed" }
                return@synchronized null
            }
            val result = stopLocked()
            closed = true
            handlerThread?.quitSafely()
            handlerThread = null
            handler = null
            window = null
            frameListener = null
            result
        }
        completed?.let(::emitSummary)
        logSummary { "close completed" }
    }

    /** 在采集线程处理单帧数据并根据刷新率切换统计桶。 */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun onFrameMetrics(
        callbackGeneration: Long,
        callbackWindow: Window,
        frameMetrics: FrameMetrics,
        dropCountSinceLastInvocation: Int,
    ) {
        var completed: FpsWindowSummary? = null
        var accepted = false
        var reason = "accepted"
        val copiedMetrics = runCatching { FrameMetrics(frameMetrics) }.getOrNull()
        val rawDurationNs = copiedMetrics?.getMetric(FrameMetrics.TOTAL_DURATION) ?: -1L
        val firstDraw = copiedMetrics?.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L
        val currentRefreshRate = readRefreshRate(callbackWindow)

        synchronized(lock) {
            if (!running || callbackGeneration != generation || callbackWindow !== window) {
                if (logLevel == FpsLogLevel.VERBOSE) {
                    reason = "late callback ignored generation=$callbackGeneration current=$generation"
                }
            } else if (firstDraw) {
                if (logLevel == FpsLogLevel.VERBOSE) {
                    reason = "first draw frame filtered"
                }
            } else if (rawDurationNs <= 0L) {
                if (logLevel == FpsLogLevel.VERBOSE) {
                    reason = "invalid total duration filtered durationNs=$rawDurationNs"
                }
            } else if (currentRefreshRate <= 0.0) {
                if (logLevel == FpsLogLevel.VERBOSE) {
                    reason = "invalid refresh rate filtered refreshRateHz=$currentRefreshRate"
                }
            } else {
                if (refreshRateHz <= 0.0) {
                    switchRefreshRateLocked(currentRefreshRate)
                } else if (kotlin.math.abs(refreshRateHz - currentRefreshRate) > REFRESH_RATE_EPSILON) {
                    completed = sceneAccumulator?.snapshot()
                    val oldRate = refreshRateHz
                    switchRefreshRateLocked(currentRefreshRate)
                    logSummary {
                        "refresh rate changed old=${formatRate(oldRate)} " +
                            "new=${formatRate(currentRefreshRate)}; old bucket finalized"
                    }
                }
                sceneAccumulator?.addCallbackDrops(dropCountSinceLastInvocation)
                intervalAccumulator?.addCallbackDrops(dropCountSinceLastInvocation)
                val sampledAtMillis = wallClockMillis()
                accepted = sceneAccumulator?.addFrame(rawDurationNs, sampledAtMillis) == true
                intervalAccumulator?.addFrame(rawDurationNs, sampledAtMillis)
                if (!accepted && logLevel == FpsLogLevel.VERBOSE) {
                    reason = "accumulator rejected durationNs=$rawDurationNs"
                }
            }
        }
        completed?.let(::emitSummary)
        logFrame(
            rawDurationNs = rawDurationNs,
            refreshRateHz = currentRefreshRate,
            dropCountSinceLastInvocation = dropCountSinceLastInvocation,
            accepted = accepted,
            reason = reason,
        )
    }

    /** 根据当前刷新率创建新的场景和日志区间累加器。 */
    private fun switchRefreshRateLocked(newRefreshRateHz: Double) {
        refreshRateHz = newRefreshRateHz
        sceneAccumulator = FpsFrameAccumulator(scene, refreshRateHz = newRefreshRateHz)
        intervalAccumulator = FpsFrameAccumulator(scene, refreshRateHz = newRefreshRateHz)
    }

    /** 停止内部监听并在锁内提取当前桶，调用方负责分发回调。 */
    private fun stopLocked(): FpsWindowSummary? {
        if (!running) {
            logSummary { "stop ignored: not running" }
            return null
        }
        running = false
        generation += 1L
        logRunnable?.let { runnable -> handler?.removeCallbacks(runnable) }
        logRunnable = null
        val activeWindow = window
        val listener = frameListener
        if (activeWindow != null && listener != null) {
            runCatching { activeWindow.removeOnFrameMetricsAvailableListener(listener) }
                .onFailure { logSummary { "listener removal failed type=${it.javaClass.simpleName}" } }
        }
        val completed = sceneAccumulator?.snapshot()
        sceneAccumulator = null
        intervalAccumulator = null
        window = null
        frameListener = null
        refreshRateHz = 0.0
        logSummary { "stop completed hasFrames=${completed != null}" }
        return completed
    }

    /** 创建采集 HandlerThread；该方法只在状态锁内调用。 */
    private fun ensureHandlerLocked() {
        if (handlerThread?.isAlive == true && handler != null) {
            return
        }
        val newThread = HandlerThread("fps-frame-metrics")
        newThread.start()
        handlerThread = newThread
        handler = Handler(newThread.looper)
        logSummary { "collector HandlerThread created" }
    }

    /** 每秒输出一次当前日志区间摘要，并安排下一次摘要。 */
    private fun logIntervalSummary(callbackGeneration: Long) {
        var interval: FpsWindowSummary? = null
        synchronized(lock) {
            if (!running || callbackGeneration != generation) {
                return
            }
            interval = intervalAccumulator?.snapshotAndReset()
            scheduleSummaryLogLocked(callbackGeneration)
        }
        val value = interval
        if (value == null) {
            return
        }
        logSummary { formatSummary("interval", value) }
    }

    /** 在日志开启时安排摘要任务，OFF 模式不创建额外定时任务。 */
    private fun scheduleSummaryLogLocked(callbackGeneration: Long) {
        if (logLevel == FpsLogLevel.OFF) {
            return
        }
        val targetHandler = handler ?: return
        val runnable = Runnable { logIntervalSummary(callbackGeneration) }
        logRunnable = runnable
        targetHandler.postDelayed(runnable, SUMMARY_INTERVAL_MILLIS)
    }

    /** 向上层分发一个已经封存的统计区间。 */
    private fun emitSummary(summary: FpsWindowSummary) {
        if (summary.uiRefreshFrameCount <= 0L) {
            logSummary { "summary skipped: no valid UI refresh frames" }
            return
        }
        logSummary { formatSummary("scene", summary) }
        runCatching { summaryListener?.invoke(summary) }
            .onFailure { logSummary { "summary listener failed type=${it.javaClass.simpleName}" } }
    }

    /** 输出单帧 VERBOSE 日志；OFF/SUMMARY 模式不构造逐帧消息。 */
    private fun logFrame(
        rawDurationNs: Long,
        refreshRateHz: Double,
        dropCountSinceLastInvocation: Int,
        accepted: Boolean,
        reason: String,
    ) {
        if (logLevel != FpsLogLevel.VERBOSE) {
            return
        }
        val budgetNs = if (refreshRateHz > 0.0) {
            (1_000_000_000L / refreshRateHz).toLong()
        } else {
            0L
        }
        val effectiveNs = if (rawDurationNs > 0L) max(budgetNs, rawDurationNs) else 0L
        Log.d(
            TAG,
            "frame scene=$scene refreshRateHz=${formatRate(refreshRateHz)} " +
                "rawDurationNs=$rawDurationNs frameBudgetNs=$budgetNs " +
                "effectiveDurationNs=$effectiveNs callbackDropCount=$dropCountSinceLastInvocation " +
                "accepted=$accepted reason=$reason",
        )
    }

    /** 只在开启 SUMMARY 时计算并输出日志消息，OFF 模式不会构造字符串。 */
    private inline fun logSummary(message: () -> String) {
        if (logLevel.ordinal >= FpsLogLevel.SUMMARY.ordinal) {
            Log.i(TAG, message())
        }
    }

    /** 将统计快照格式化为稳定的具名字段日志。 */
    private fun formatSummary(scope: String, summary: FpsWindowSummary): String {
        val rawFps = summary.rawFps?.let { formatRate(it) } ?: "none"
        val normalizedFps = summary.normalizedFps60?.let { formatRate(it) } ?: "none"
        return "fps scope=$scope scene=${summary.scene} " +
            "refreshRateHz=${formatRate(summary.refreshRateHz)} " +
            "uiRefreshFrameCount=${summary.uiRefreshFrameCount} " +
            "activeDurationMs=${summary.activeDurationMs} rawFps=$rawFps " +
            "normalizedFps60=$normalizedFps " +
            "averageFrameDurationNs=${summary.averageFrameDurationNs} " +
            "maxFrameDurationNs=${summary.maxFrameDurationNs} " +
            "callbackDropCount=${summary.callbackDropCount}"
    }

    /** 读取当前 Window 的刷新率，读取失败时返回 0 让上层过滤该帧。 */
    private fun readRefreshRate(targetWindow: Window): Double {
        return runCatching {
            val display = targetWindow.decorView.display
            (display?.refreshRate ?: targetWindow.windowManager.defaultDisplay.refreshRate)
                .toDouble()
        }.getOrDefault(0.0)
    }

    /** 校验场景名，阻止动态长文本进入日志和服务端维度。 */
    private fun validateScene(sceneName: String) {
        require(sceneName.isNotBlank()) { "FPS scene must not be blank" }
        require(sceneName.length <= MAX_SCENE_LENGTH) {
            "FPS scene must be at most $MAX_SCENE_LENGTH characters"
        }
        require(sceneName.none { it.isISOControl() }) {
            "FPS scene must not contain control characters"
        }
    }

    /** 以固定小数位输出刷新率和 FPS，保证日志字段稳定。 */
    private fun formatRate(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private companion object {
        const val TAG = "FpsHelperV2"
        const val DEFAULT_SCENE = "unknown"
        const val MAX_SCENE_LENGTH = 128
        const val REFRESH_RATE_EPSILON = 0.01
        const val SUMMARY_INTERVAL_MILLIS = 1_000L
    }
}
