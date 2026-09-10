package com.example.nativelib

import android.util.Printer
import com.example.nativelib.LooperMonitor.AttachmentState

/** 隔离私有字段访问，允许测试模拟反射受限与第三方替换。 */
internal interface LooperPrinterAccess {
    fun read(): Printer?
    fun write(printer: Printer?)
}

internal class LooperPrinterHook(
    private val access: LooperPrinterAccess,
    private val core: LooperDispatchCore,
    private val uptimeMillis: () -> Long,
    private val report: (Exception) -> Unit,
) {
    @Volatile var state = AttachmentState.PENDING
        private set
    private var printer: ForwardingPrinter? = null
    private var lastCheck = 0L
    private var reflectionFailed = false
    private val dispatching = ThreadLocal<Boolean>()

    @Synchronized fun install() {
        if (state == AttachmentState.CLOSED || reflectionFailed) return
        try {
            val current = access.read()
            if (current === printer && printer != null) return
            // 不同实例（包括不同 ClassLoader）的同类包装不得互相套娃。
            if (current != null && current.javaClass.name == ForwardingPrinter::class.java.name &&
                (current !is ForwardingPrinter || current.owner !== this)) {
                state = AttachmentState.UNAVAILABLE
                return
            }
            core.resetDispatch()
            val next = ForwardingPrinter(current)
            access.write(next)
            printer = next
            state = AttachmentState.ATTACHED
            lastCheck = uptimeMillis()
        } catch (e: Exception) { failed(e) }
    }
    @Synchronized fun onIdle() {
        if (uptimeMillis() - lastCheck >= 60_000L) {
            lastCheck = uptimeMillis()
            install()
        }
    }
    @Synchronized fun unavailable() { if (state != AttachmentState.CLOSED) state = AttachmentState.UNAVAILABLE }
    @Synchronized fun close() {
        if (state == AttachmentState.CLOSED) return
        if (!reflectionFailed && printer != null) {
            try {
                if (access.read() === printer) access.write(printer!!.origin)
            } catch (e: Exception) { failed(e) }
        }
        state = AttachmentState.CLOSED
        printer = null
    }
    private fun failed(e: Exception) {
        reflectionFailed = true
        state = AttachmentState.REFLECTION_UNAVAILABLE
        report(e)
    }
    private inner class ForwardingPrinter(val origin: Printer?) : Printer {
        val owner: LooperPrinterHook get() = this@LooperPrinterHook
        private val forwarding = ThreadLocal<Boolean>()
        override fun println(log: String?) {
            // 旧包装可能仍在第三方链中：实例级防转发环，owner 级保证分发一次。
            if (forwarding.get() == true) return
            val outermost = dispatching.get() != true
            forwarding.set(true)
            if (outermost) dispatching.set(true)
            try {
                origin?.takeUnless { it === this }?.println(log)
                if (outermost && state != AttachmentState.CLOSED) core.dispatch(log)
            } finally {
                forwarding.remove()
                if (outermost) dispatching.remove()
            }
        }
    }
}
