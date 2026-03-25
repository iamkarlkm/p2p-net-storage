
package javax.net.p2p;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Administrator
 */
/**
 * 该线程池的等待队列最大长度默认为int的最大值，随口默写出来就是2147483647（2^31 -1，
 * 高中物理老师说过一句话，记住一些固定的数字可以预判一些问题）。线程池在提交任务时，
 * 如果线程池未达到最大线程数，则起线程执行任务，在达到最大值后，会放入等待队列，
 * 按默认的int最大值，很容易造成内存溢出。所以通常会选择自行构造线程池
 */
@Slf4j
public class ExecutorServicePool {

	//延迟执行线程池
	public static final ScheduledExecutorService THREAD_DELAY_POOLS = Executors.newScheduledThreadPool(1);

	//异步写日志
	public static final ExecutorService LOG_SEQUENCE_SINGLE_POOLS = new ThreadPoolExecutor(1, 1,
			60L, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(1024));

	//MINIO写入校验任务执行池
	public static final ExecutorService FILE_SYNC_POOLS = new ThreadPoolExecutor(16, 32,
			60L, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(4096));
	//线程监控管理器
	//public static final ScheduledExecutorService POOL_MONITOR_TIMER = Executors.newScheduledThreadPool(3);

	/**
	 * STREAM 并发流执行线程池
	 */
	public final static ForkJoinPool FORK_JOIN_POOL = new ForkJoinPool();

	@PreDestroy
	public void onExit() {
		log.info("###ExecutorService THREAD_POOLS STOPing###");
		try {
			THREAD_DELAY_POOLS.shutdown();
			LOG_SEQUENCE_SINGLE_POOLS.shutdown();
			FORK_JOIN_POOL.shutdown();
		} catch (Exception e) {
			log.error("", e);
			;
		}
		log.info("###ExecutorService THREAD_POOLS STOPED###");
	}

	@PostConstruct
	public void onInit() {
		//检测线程死锁,5分钟未成功执行,强制中断
		//POOL_MONITOR_TIMER.scheduleAtFixedRate(this::checkDeadLock, 1L, 5L, TimeUnit.MINUTES);
	}

	public static void checkDeadLock(int limit) {
		System.out.println("checkDeadLock -> Thread.activeCount:" + Thread.activeCount());

	}

	public static void main(String[] args) {
		// java.util.concurrent.
	}
}
