package com.example.nativelib.jank

import android.app.Application
import com.bytedance.rheatrace.RheaTrace3
import java.io.File

/**
 * RheaTrace3 的最小适配边界。
 *
 * 生产环境使用真实静态 API；JVM/仪器测试可以替换 [JankTraceAdapterProvider.current]，
 * 从而不需要把 Rhea 的运行行为伪装成网络或队列行为。
 */
internal interface JankTraceAdapter {
    fun initOnline(
        application: Application,
        config: RheaTrace3.OnlineTraceConfig,
    ): RheaTrace3.InitResult

    fun exportJankTrace(
        event: RheaTrace3.JankEvent,
        callback: RheaTrace3.ExportCallback,
    ): RheaTrace3.ExportRequestResult

    fun pendingJankFiles(): List<File>

    fun deleteJankFile(artifact: File): Boolean
}

/** 可替换的 Rhea 适配器；默认实现不改变线上静态 API 语义。 */
internal object JankTraceAdapterProvider {
    @Volatile
    var current: JankTraceAdapter = RealJankTraceAdapter
}

private object RealJankTraceAdapter : JankTraceAdapter {
    override fun initOnline(
        application: Application,
        config: RheaTrace3.OnlineTraceConfig,
    ): RheaTrace3.InitResult = RheaTrace3.initOnline(application, config)

    override fun exportJankTrace(
        event: RheaTrace3.JankEvent,
        callback: RheaTrace3.ExportCallback,
    ): RheaTrace3.ExportRequestResult = RheaTrace3.exportJankTrace(event, callback)

    override fun pendingJankFiles(): List<File> = RheaTrace3.getPendingJankFiles()

    override fun deleteJankFile(artifact: File): Boolean = RheaTrace3.deleteJankFile(artifact)
}
