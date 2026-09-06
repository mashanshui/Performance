package com.example.performance

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nativelib.PerformanceSdk

/**
 * 用于真机验收 FPS 采集的可重复测试页面。
 *
 * 页面只提供会改变真实 UI 刷新的控件，FPS 数值由 SDK 的 FpsHelperV2 日志输出，
 * 这样测试控件本身不会增加实时 FPS 面板带来的额外绘制开销。
 */
class TestFPSActivity : AppCompatActivity() {
    private companion object {
        private const val SCENE_IDLE = "fps_test_idle"
        private const val SCENE_SCROLL = "fps_test_scroll"
        private const val SCENE_SLOW_FRAME = "fps_test_slow_frame"
        private const val AUTO_SCROLL_STEP_PX = 12
        private const val SLOW_FRAME_BLOCK_MILLIS = 120L
        private const val DEMO_ROW_COUNT = 400
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var recyclerView: RecyclerView
    private lateinit var statusTextView: TextView
    private var autoScrollRunning = false

    /** 在主线程创建列表和测试控件，并为 SDK 设置进入页面时的稳定场景。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_test_fpsactivity)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        recyclerView = findViewById(R.id.recyclerView)
        statusTextView = findViewById(R.id.statusTextView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = FpsListAdapter(buildDemoRows())

        bindTestControls()
        applyScene(SCENE_IDLE, "空闲场景：停留时不主动制造刷新")
    }

    /** 页面重新可见时恢复空闲场景，避免旋转或重新进入时沿用旧测试状态。 */
    override fun onResume() {
        super.onResume()
        stopAutoScrollInternal(updateScene = false)
        applyScene(SCENE_IDLE, "空闲场景：等待手动开始测试")
    }

    /** 页面进入后台时停止下一帧滚动回调，避免后台继续制造测试工作。 */
    override fun onPause() {
        stopAutoScrollInternal(updateScene = false)
        statusTextViewOrNull()?.let { it.text = "页面已暂停：滚动回调已停止" }
        super.onPause()
    }

    /** 页面销毁时移除主线程回调并释放列表引用，防止测试任务持有 Activity。 */
    override fun onDestroy() {
        stopAutoScrollInternal(updateScene = false)
        mainHandler.removeCallbacksAndMessages(null)
        recyclerView.adapter = null
        super.onDestroy()
    }

    /** 绑定四个测试按钮；所有点击回调都在主线程触发真实页面行为。 */
    private fun bindTestControls() {
        findViewById<Button>(R.id.startAutoScrollButton).setOnClickListener {
            startAutoScroll()
        }
        findViewById<Button>(R.id.stopAutoScrollButton).setOnClickListener {
            stopAutoScroll()
        }
        findViewById<Button>(R.id.injectSlowFrameButton).setOnClickListener {
            injectSlowFrame()
        }
        findViewById<Button>(R.id.restoreDefaultSceneButton).setOnClickListener {
            restoreDefaultScene()
        }
    }

    /** 开始逐帧小幅滚动；postOnAnimation 与下一次显示同步，适合制造连续真实刷新。 */
    private fun startAutoScroll() {
        if (autoScrollRunning) {
            updateStatus("自动滚动已在运行：请观察 FpsHelperV2 摘要日志")
            return
        }
        autoScrollRunning = true
        applyScene(SCENE_SCROLL, "滚动场景：每帧向下移动 ${AUTO_SCROLL_STEP_PX}px")
        recyclerView.postOnAnimation(autoScrollRunnable)
    }

    /** 停止自动滚动并切回空闲场景；重复点击保持幂等。 */
    private fun stopAutoScroll() {
        stopAutoScrollInternal(updateScene = true)
        updateStatus("空闲场景：自动滚动已停止")
    }

    /** 执行滚动状态转换和回调移除；调用线程必须是主线程。 */
    private fun stopAutoScrollInternal(updateScene: Boolean) {
        mainHandler.removeCallbacks(autoScrollRunnable)
        recyclerViewOrNull()?.removeCallbacks(autoScrollRunnable)
        val wasRunning = autoScrollRunning
        autoScrollRunning = false
        if (updateScene) {
            applyScene(SCENE_IDLE, "空闲场景：滚动回调已停止")
        } else if (wasRunning) {
            updateStatus("页面离开：滚动回调已停止")
        }
    }

    /** 在主线程只阻塞一次约 120ms，用于验证慢帧最大耗时；不会启动持续性卡顿。 */
    private fun injectSlowFrame() {
        stopAutoScrollInternal(updateScene = false)
        applyScene(SCENE_SLOW_FRAME, "慢帧场景：即将阻塞主线程 ${SLOW_FRAME_BLOCK_MILLIS}ms")
        mainHandler.post {
            // 让场景状态先提交，再阻塞一次主线程，FrameMetrics 会记录这次慢绘制。
            SystemClock.sleep(SLOW_FRAME_BLOCK_MILLIS)
            updateStatus("慢帧场景：阻塞完成，请查看最大帧耗时日志")
        }
    }

    /** 让 SDK 清除自定义场景并恢复 Activity 完整类名维度。 */
    private fun restoreDefaultScene() {
        stopAutoScrollInternal(updateScene = false)
        PerformanceSdk.current()?.setFpsScene(this, null)
        updateStatus("已恢复默认场景：${javaClass.name}")
    }

    /** 将页面场景同步给 SDK，并在页面上显示本次测试动作。 */
    private fun applyScene(scene: String, status: String) {
        PerformanceSdk.current()?.setFpsScene(this, scene)
        updateStatus(status)
    }

    /** 更新控件状态文字；该方法只在主线程调用。 */
    private fun updateStatus(status: String) {
        if (::statusTextView.isInitialized) {
            statusTextView.text = status
        }
    }

    /** 构造足够长且内容有差异的列表，确保滚动时持续发生绑定和布局。 */
    private fun buildDemoRows(): List<String> {
        return List(DEMO_ROW_COUNT) { index ->
            "FPS 测试行 ${index + 1}：滚动时用于产生稳定的列表布局与绘制工作"
        }
    }

    /** 返回当前列表控件；销毁阶段控件可能尚未初始化，调用方只用于安全清理。 */
    private fun recyclerViewOrNull(): RecyclerView? {
        return if (::recyclerView.isInitialized) recyclerView else null
    }

    /** 返回当前状态控件；暂停阶段允许在控件未初始化时跳过状态更新。 */
    private fun statusTextViewOrNull(): TextView? {
        return if (::statusTextView.isInitialized) statusTextView else null
    }

    /** 下一帧滚动任务；任务自循环直到页面暂停或用户点击停止。 */
    private val autoScrollRunnable = object : Runnable {
        /** 在主线程推进列表一小步，并把下一次执行交给下一帧。 */
        override fun run() {
            if (!autoScrollRunning || isFinishing || isDestroyed) {
                return
            }
            recyclerView.scrollBy(0, AUTO_SCROLL_STEP_PX)
            recyclerView.postOnAnimation(this)
        }
    }

    /** 使用稳定文本绑定一个列表项，避免测试过程中注入异常或无条件睡眠。 */
    private class FpsListAdapter(
        private val items: List<String>,
    ) : RecyclerView.Adapter<FpsListViewHolder>() {
        /** 创建列表项 ViewHolder；执行于 RecyclerView 所在线程。 */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FpsListViewHolder {
            val itemView = LayoutInflater.from(parent.context)
                .inflate(R.layout.test_fps_item, parent, false)
            return FpsListViewHolder(itemView)
        }

        /** 绑定列表文本；该方法不执行阻塞和异常注入。 */
        override fun onBindViewHolder(holder: FpsListViewHolder, position: Int) {
            holder.textView.text = items[position]
        }

        /** 返回固定测试数据量，保证每次页面进入的滚动长度一致。 */
        override fun getItemCount(): Int = items.size
    }

    /** 保存测试列表项中的文本控件引用，减少绑定时的查找开销。 */
    private class FpsListViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        /** 列表项展示文本；布局由 test_fps_item.xml 提供。 */
        val textView: TextView = itemView.findViewById(R.id.textView)
    }
}
