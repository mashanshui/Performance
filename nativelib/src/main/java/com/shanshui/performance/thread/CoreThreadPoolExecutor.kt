package com.shanshui.performance.thread

import java.util.concurrent.BlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * @author mashanshui
 * @since 2025/12/24
 *
 * corePoolSize（核心线程数量）
 * 在线程池被创建时，会预先创建一定数量的核心线程，并将它们保持活动状态，以便能够立即执行任务。除非手动调用了
 * allowCoreThreadTimeOut 方法用来申明核心线程需要退出，否则核心线程启动后便一直是存活不退出的状态，
 * 即使当前没有任务可执行，也不会被销毁。通过设置适当的核心线程数，可以平衡线程池的性能和资源消耗。如果核心线程数设置得太小，
 * 可能无法及时处理到达的任务，导致性能下降。而设置得太大则可能会浪费系统资源。
 *
 * maximumPoolSize（最大线程数量）
 * 当有新任务到来，而核心线程又全在执行任务没法响应这些新的任务时，这些新任务会放在缓存队列中，如果缓存队列也满了，
 * 线程池就会启动新的线程来执行这些任务，这些线程被称为非核心线程，非核心线程的数量加上核心线程的数量就是线程池最大线程数量。
 *
 * keepAliveTime（非核心线程的空闲时间）
 * keepAliveTime 定义了非核心线程在空闲状态下的存活时间。如果一个非核心线程的空闲时间达到了keepAliveTime 所设定的值，
 * 那么它就会被线程池回收销毁以减少资源消耗。
 *
 * unit（时间单位）
 * keepAliveTime 的的时间单位，如秒，分等
 *
 * workQueue（任务队列）
 * 线程池中用于存储待执行任务的缓存队列，常见的缓存队列有 LinkedBlockingDeque 和 SynchronousQueue 这两种：
 * LinkedBlockingDeque 是一个双向的并发队列，主要用于 CPU 线程池；SynchronousQueue 虽然也是一个队列，但它并不能存储任务，
 * 所以该队列会将添加进来的任务直接交给新的线程去处理，而不会存储这些任务，主要用于 IO 线程池中。任务队列在线程池中起到重要的作用，
 * 它可以帮助控制并发任务的数量，平衡任务的生产和消费速度，以及提供任务的排队机制。
 *
 * threadFactory（线程工厂）用于创建线程的工厂对象。可用于自定义线程的创建方式和属性，包括线程的名称、优先级、线程组等。
 * 在虚拟内存优化时，也提到过可以使用自定义的线程工厂，来创建栈空间只有 512 KB 的线程。
 *
 * rejectedExecutionHandler（拒绝策略）
 * 当线程池已经饱和，无法再接受新的任务时，拒绝策略定义了对这些新任务的处理方式。默认的策略会抛出RejectedExecutionException 异常，并阻止任务的提交。
 *
 */
open class CoreThreadPoolExecutor : ThreadPoolExecutor {
    constructor(
        corePoolSize: Int,
        maximumPoolSize: Int,
        keepAliveTime: Long,
        workQueue: BlockingQueue<Runnable>,
        threadFactory: ThreadFactory
    ) : super(
        corePoolSize,
        maximumPoolSize,
        keepAliveTime,
        TimeUnit.SECONDS,
        workQueue,
        threadFactory
    ) {
        setRejectedExecutionHandler(CoreRejectedExecutionHandler())
    }

    class CoreRejectedExecutionHandler : RejectedExecutionHandler {
        override fun rejectedExecution(r: Runnable, pool: ThreadPoolExecutor) {
            // do nothing
        }
    }
}