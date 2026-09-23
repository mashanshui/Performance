package com.shanshui.performance.core

/** 组件生命周期阶段，区分进程准备、采集启动和关闭责任。 */
interface PerformanceComponent {
    /** 当前组件的稳定功能标识。 */
    val id: String

    /** 在普通采集判断之前执行必要的进程级准备。 */
    fun prepare()

    /** 启动当前组件持有的采集与上传资源。 */
    fun start()

    /** 释放当前组件拥有的资源；实现必须幂等。 */
    fun close()
}

/** 显式装配组件的统一运行时，负责重复校验、回滚和逆序关闭。 */
class PerformanceRuntime private constructor(
    /** 本次装配使用的不可变组件列表。 */
    private val components: List<PerformanceComponent>,
    /** 用于重复初始化判断的稳定配置指纹。 */
    private val descriptor: String,
) : AutoCloseable {
    /** 已成功启动的组件，按启动顺序保存以支持逆序回滚。 */
    private val startedComponents = ArrayList<PerformanceComponent>()

    /** 防止重复关闭和重复释放资源。 */
    private var closed = false

    /** 运行所有组件的准备和启动阶段，失败时清理已创建组件。 */
    fun start() {
        check(!closed) { "performance runtime is closed" }
        try {
            components.forEach { component -> component.prepare() }
            components.forEach { component ->
                startedComponents += component
                component.start()
            }
        } catch (throwable: Throwable) {
            closeStarted()
            throw throwable
        }
    }

    /** 按启动逆序关闭组件并保证幂等。 */
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        closeStarted()
    }

    /** 释放已启动组件，组件自身负责清理部分初始化资源。 */
    private fun closeStarted() {
        startedComponents.asReversed().forEach { component ->
            runCatching { component.close() }
        }
        startedComponents.clear()
    }

    /** 通过显式不可变组件列表创建运行时并拒绝重复功能。 */
    companion object {
        /** 创建尚未启动的组件运行时。 */
        fun create(
            components: List<PerformanceComponent>,
            descriptor: String = components.joinToString(",") { component -> component.id },
        ): PerformanceRuntime {
            val stableComponents = components.toList()
            val duplicateIds = stableComponents
                .groupingBy { component -> component.id }
                .eachCount()
                .filterValues { count -> count > 1 }
                .keys
            require(duplicateIds.isEmpty()) {
                "duplicate performance components: ${duplicateIds.sorted().joinToString()}"
            }
            return PerformanceRuntime(stableComponents, descriptor)
        }
    }

    /** 返回本次装配的稳定配置指纹，供运行时注册表比较。 */
    internal fun descriptor(): String = descriptor
}

/** 进程级显式装配注册表，统一处理相同请求复用与冲突请求拒绝。 */
object PerformanceRuntimeRegistry {
    /** 保护当前进程唯一运行时的锁。 */
    private val lock = Any()

    /** 当前已注册的运行时实例。 */
    private var current: PerformanceRuntime? = null

    /** 注册并启动组件；相同描述返回现有实例，不同描述抛出冲突。 */
    fun initialize(
        components: List<PerformanceComponent>,
        descriptor: String,
    ): PerformanceRuntime {
        synchronized(lock) {
            current?.let { existing ->
                check(existing.descriptor() == descriptor) {
                    "performance runtime is already initialized with a different configuration"
                }
                return existing
            }
            val runtime = PerformanceRuntime.create(components, descriptor)
            runtime.start()
            current = runtime
            return runtime
        }
    }

    /** 关闭并清空当前注册运行时；重复调用安全。 */
    fun close() {
        synchronized(lock) {
            current?.close()
            current = null
        }
    }

    /** 清理 JVM 测试中的全局状态。 */
    internal fun resetForTesting() {
        close()
    }
}
