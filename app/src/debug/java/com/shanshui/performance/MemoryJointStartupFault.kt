package com.shanshui.performance

import android.app.Application
import android.os.Process
import android.util.Log
import java.io.File
import java.util.UUID

/** 仅显式 Debug 联调使用的启动故障探针，不新增 SDK 公开 API。 */
object MemoryJointStartupFault {
    /** 在生产存储恢复的持久化边界终止真实进程，正常构建或无标记时不介入。 */
    @JvmStatic fun run(application: Application) {
        if (!BuildConfig.DEBUG || !BuildConfig.PERFORMANCE_MEMORY_JOINT_TEST) return
        /** 标记由宿主在已备份的测试目录内写入，只允许两个固定边界。 */
        val flag = File(application.noBackupFilesDir, "memory-joint-startup-fault")
        if (!flag.exists()) return
        /** 不读取或输出任何凭据。 */
        val boundary = flag.readText().trim()
        require(boundary in setOf("write", "remove"))
        /** 反射调用内部生产文件与存储实现，不复制封存逻辑。 */
        val atomicType = Class.forName("com.shanshui.performance.memory.MemoryAtomicFile")
        /** 命中指定边界后 SIGKILL，避免依赖正常退出回调。 */
        val atomic = atomicType.constructors.first { it.parameterCount == 1 }.newInstance({ stage: String ->
            if (stage == boundary) {
                Log.i("MemoryJointFault", "startup boundary reached stage=$stage")
                Process.killProcess(Process.myPid())
                error("process termination requested")
            }
            Unit
        })
        /** 与默认配置相同的内存专属路径和持久化设置。 */
        val storeType = Class.forName("com.shanshui.performance.memory.MemoryEventStore")
        /** 生产默认 Gson 配置。 */
        val gson = Class.forName("com.google.gson.Gson").getDeclaredConstructor().newInstance()
        /** 新身份只用于识别旧轮，不创建新信封；恢复动作到故障点即终止。 */
        val identity = UUID.randomUUID().toString()
        /** 本次恢复实际使用生产快照及封存文件。 */
        val store = storeType.constructors.first { it.parameterCount == 9 }.newInstance(
            File(application.noBackupFilesDir, "performance-memory-reporter"), 20L * 1024 * 1024,
            90L * 86_400_000, 10, { _: () -> String -> Unit }, gson,
            { UUID.randomUUID().toString() }, { System.currentTimeMillis() }, atomic,
        )
        storeType.getMethod("restore", String::class.java).invoke(store, identity)
        error("startup fault did not reach expected boundary")
    }
}
