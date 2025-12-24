package com.example.nativelib.thread

import android.os.Process
import java.util.concurrent.LinkedBlockingDeque

/**
 * @author mashanshui
 * @since 2025/12/24
 *
 * CPU 线程池的主要作用是有效地管理和执行 CPU 密集型任务，以达到充分发挥 CPU 资源，提升应用的性能的目的。
 * 带着这个目的，我们看一下 CPU 线程池的入参要如何设置。
 *
 * corePoolSize 核心线程数
 *首先是 corePoolSize 核心线程数。CPU 线程池是用来执行 CPU 类型任务的，所以它的核心线程数量一般为 CPU 的核数，
 * 理想情况下每一个 CPU 核运行一个线程，这种情况下既能充分发挥 CPU 的性能，还减少了频繁调度导致的 CPU 损耗。虽然
 * 程序在实际运行过程中无法达到理想情况，但是将核心线程数设置为 CPU 核数个依然是最稳妥的配置。
 *
 * maximumPoolSize 最大线程数
 *对于 CPU 线程池来说，每个 CPU 核心对应一个线程，就能将 CPU 充分发挥出来，如果线程数量超过了 CPU 核数，只会带来
 * 不必要的 CPU 切换和调度导致的性能损耗。因此 CPU 线程池的最大线程数就是核心线程数，当 CPU 线程池中的线程已处于
 * 忙碌状态而无法处理新任务时，新来的任务放入到任务缓存容器中。
 *
 * keepAliveTime 线程存活时间
 *既然 CPU 线程池没有非核心线程，所以 keepAliveTime 这个表示非核心线程数的存活时间的值设置为 0 即可。
 *
 * workQueue 任务缓存队列
 *CPU 线程池中一般使用 LinkedBlockingDeque，这是一个可以设置容量并支持并发的队列。由于 CPU 线程池的线程数量
 * 较少，如果较多任务来到且没有空闲的核心线程可以执行任务时，这些任务就需要放在缓存队列中。缓存队列的容量默认情况
 * 下是无限大的，但是这样的容量设置并不是一个好的配置。如果程序有些异常的死循环逻辑不断地往队列添加任务，而这个
 * 队列却能一直缓存任务，那么就很难发现异常。但是当我们将这个队列设置成有限的，比如 512 个，那么异常的死循环就会
 * 将队列打满，接下来的任务进入到拒绝策略的逻辑中，这样我们就可以在拒绝策略添加监控，就能及时发现这个异常了。
 *
 * 拒绝策略
 *当缓存队列中存储的任务达到上限，并且也没有可用的非核心线程来处理这些无法放在缓存队列中的任务，那么这些任务就会
 * 进入到一个异常的兜底函数 rejectedExecution 中，Executors 对象创建的线程池使用的是默认的兜底策略，其代码
 * 实现如下，可以看到此时会直接抛出异常。
 *
 */
class CPUThreadPoolExecutor : CoreThreadPoolExecutor {
    companion object {
        private val CPU_COUNT = Runtime.getRuntime().availableProcessors()
        private val CORE_POOL_SIZE: Int = CPU_COUNT
        private val MAX_POOL_SIZE: Int = CPU_COUNT
        private const val BLOCK_QUEUE_CAPACITY: Int = 512
        private const val THREAD_PREFIX = "CPUThread"

        val instance: CPUThreadPoolExecutor by lazy {
            CPUThreadPoolExecutor()
        }
    }

    private constructor() : super(
        CORE_POOL_SIZE,
        MAX_POOL_SIZE,
        0,
        LinkedBlockingDeque<Runnable>(BLOCK_QUEUE_CAPACITY),
        CoreThreadFactory(THREAD_PREFIX, Process.THREAD_PRIORITY_DISPLAY)
    )
}