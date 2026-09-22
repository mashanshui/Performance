package com.shanshui.performance.thread

import android.os.Process
import java.util.concurrent.SynchronousQueue

/**
 * @author mashanshui
 * @since 2025/12/24
 *
 * 当系统在进行 IO 操作时，会交给 DMA（Direct Memory Access，直接存储器访问）硬件来处理，因此不需要通过 CPU
 * 便能进行数据的传输，所以 IO 任务对 CPU 的资源消耗是很少的。对于 IO 线程池来说，由于 IO 任务对 CPU 资源消耗
 * 不高，所以每来一个 IO 任务便可以直接交由一个独立的线程去执行，并不需要放入缓存队列中，这样可以保证每个 IO 任务
 * 都能及时响应，如果多个 IO 任务复用同一个线程，那么当某个 IO 任务阻塞线程时，会导致其他的 IO 任务也无法执行。
 * 了解了这一特性，我们来看看 IO 线程池的入参要如何设置。
 *
 * corePoolSize 核心线程数
 *IO 线程池的 corePoolSize 核心线程数没有定性规定，它和我们应用程序的业务场景有关。如果 IO 任务比较多，就得设置
 * 得多一些，因为太少了就会因为 IO 线程频率创建和销毁而产生性能损耗。如果业务场景中的 IO 任务不多，直接设置为 0
 * 个也没问题，通过 Exectors 创建出来的 IO 线程池的核心线程数量就是为 0 个。
 *
 * maximumPoolSize 最大线程数
 *IO 任务实际上消耗的 CPU 资源是非常少的，当需要读写数据的时候，系统会交给 DMA 芯片去进行操作，此时调度器就会让
 * 当前线程进行休眠，并且把 CPU 资源切换给其他的线程去使用。所以对于 IO 线程池中 maximumPoolSize 最大线程数，
 * 可以多设置一些，确保每个 IO 任务都能有一个对应的线程来执行，这样可以保障 IO 任务能尽快得到执行。一般来说，
 * 中小型应用设置几十个线程数就足够了，即使是大型应用，也不建议将数量设置得特别大，比如通过 Exectors 创建出来的
 * IO 线程池的最大线程数就是无限大的，这样会导致当出现死循环等异常时，IO 线程池中的任务无法进入到拒绝策略。
 *
 * 缓存队列
 *对于 IO 线程池来说，是不需要缓存任务的，因为每来一个任务，线程池都会启用一个独立的线程去执行这个任务，所以对于
 * IO 线程池来说，一般都是传入 SynchronousQueue 这个容量为 0 的队列。
 *
 * keepAliveTime 线程存活时间
 *非核心线程的存活时间也需要根据业务场景来进行决定，如果业务时很频繁的出现大量 IO 的场景，我们可以将存活时间设置
 * 的长一些，如果是低频的大量 IO 场景，可以将存活时间设置的短一些，这样可以减少无用线程对内存资源的消耗。
 *
 * 异常兜底策略
 *IO 线程池的异常兜底的策略可以和 CPU 线程池的一样，将异常上报，然后将进入到兜底的任务用一个兜底的线程去执行即可。
 *
 */
class IOThreadPoolExecutor : CoreThreadPoolExecutor {
    companion object {
        private const val CORE_POOL_SIZE: Int = 1
        private const val MAX_POOL_SIZE: Int = 64
        private const val KEEP_ALIVE_TIME: Long = 10
        private const val THREAD_PREFIX = "IOThread"

        val instance: IOThreadPoolExecutor by lazy {
            IOThreadPoolExecutor()
        }
    }

    private constructor() : super(
        CORE_POOL_SIZE,
        MAX_POOL_SIZE,
        KEEP_ALIVE_TIME,
        SynchronousQueue<Runnable>(),
        CoreThreadFactory(THREAD_PREFIX, Process.THREAD_PRIORITY_LESS_FAVORABLE)
    )
}