package javax.net.p2p.common;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.*;

/**
 * 线程池集中管理类 该线程池的等待队列最大长度默认为int的最大值，随口默写出来就是2147483647（2^31 -1，
 * 高中物理老师说过一句话，记住一些固定的数字可以预判一些问题）。线程池在提交任务时，
 * 如果线程池未达到最大线程数，则起线程执行任务，在达到最大值后，会放入等待队列， 按默认的int最大值，很容易造成内存溢出。所以通常会选择自行构造线程池
 */
@Slf4j
public class ExecutorServicePool {

    /**
     * 客户端连接请求等待服务器回应线程池,P2P专用底层自动创建和释放
     */
    public static ExecutorService P2P_REFERENCED_CHANNEL_AWAIT_POOLS;
    /**
     * 客户端请求异步执行线程池,P2P专用底层自动创建和释放
     */
    public static ExecutorService P2P_REFERENCED_CLIENT_ASYNC_POOLS;

    /**
     * 服务器等待客户端回应线程池,P2P专用底层自动创建和释放
     */
    public static ExecutorService P2P_REFERENCED_SERVER_AWAIT_POOLS;
    /**
     * 服务器异步执行线程池,P2P专用底层自动创建和释放
     */
    public static ExecutorService P2P_REFERENCED_SERVER_ASYNC_POOLS;

    @PreDestroy
    public void onExit() {
         log.info("###@PreDestroy onExit()###");
        releaseP2PClientPools();
        releaseP2PServerPools();
    }

    @PostConstruct
    public void onInit() {
        //检测线程死锁,5分钟未成功执行,强制中断
        //POOL_MONITOR_TIMER.scheduleAtFixedRate(this::checkDeadLock, 1L, 5L, TimeUnit.MINUTES);
    }

    /**
     * 初始化客户端消息等待任务池,客户端异步执行任务池
     */
    public synchronized static void createClientPools() {
        if (P2P_REFERENCED_CHANNEL_AWAIT_POOLS != null && P2P_REFERENCED_CLIENT_ASYNC_POOLS != null){
            return;
        }
        log.info("###ExecutorService Client Using THREAD_POOLS Creating###");
        try {
            if (P2P_REFERENCED_CHANNEL_AWAIT_POOLS == null) {
                P2P_REFERENCED_CHANNEL_AWAIT_POOLS = new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors() * 2, Runtime.getRuntime().availableProcessors()*2,
                    60L, TimeUnit.MINUTES,
                    new ArrayBlockingQueue<>(4096));
            }

            if (P2P_REFERENCED_CLIENT_ASYNC_POOLS == null) {
                P2P_REFERENCED_CLIENT_ASYNC_POOLS = new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors() * 2, Runtime.getRuntime().availableProcessors() * 2,
                    60L, TimeUnit.MINUTES,
                    new ArrayBlockingQueue<>(4096));
            }

        } catch (Exception e) {
            log.error("", e);
        }
        log.info("###ExecutorService Client Using THREAD_POOLS Created###");
    }

    public synchronized static void releaseP2PClientPools() {
        log.info("###ExecutorService Client Using THREAD_POOLS STOPing###");
        try {
            if (P2P_REFERENCED_CHANNEL_AWAIT_POOLS != null) {
                P2P_REFERENCED_CHANNEL_AWAIT_POOLS.shutdown();
                P2P_REFERENCED_CHANNEL_AWAIT_POOLS = null;
            }
            if (P2P_REFERENCED_CLIENT_ASYNC_POOLS != null) {
                P2P_REFERENCED_CLIENT_ASYNC_POOLS.shutdown();
                P2P_REFERENCED_CLIENT_ASYNC_POOLS = null;
            }
        } catch (Exception e) {
            log.error("", e);
            if (P2P_REFERENCED_CHANNEL_AWAIT_POOLS != null) {
                P2P_REFERENCED_CHANNEL_AWAIT_POOLS.shutdownNow();
                P2P_REFERENCED_CHANNEL_AWAIT_POOLS = null;
            }
            if (P2P_REFERENCED_CLIENT_ASYNC_POOLS != null) {
                P2P_REFERENCED_CLIENT_ASYNC_POOLS.shutdownNow();
                P2P_REFERENCED_CLIENT_ASYNC_POOLS = null;
            }
        }
        log.info("###ExecutorService Client Using THREAD_POOLS STOPED###");
    }

    /**
     * 初始化服务器消息等待任务池,服务器异步执行任务池
     */
    public synchronized static void createServerPools() {
        if (P2P_REFERENCED_SERVER_AWAIT_POOLS != null && P2P_REFERENCED_SERVER_ASYNC_POOLS != null){
            return;
        }
        log.info("###ExecutorService Server Using THREAD_POOLS Creating###");
        try {
            if (P2P_REFERENCED_SERVER_AWAIT_POOLS == null) {
                P2P_REFERENCED_SERVER_AWAIT_POOLS = new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors()*2, Runtime.getRuntime().availableProcessors()*2,
                    60L, TimeUnit.MINUTES,
                    new ArrayBlockingQueue<>(4096));
            }

            if (P2P_REFERENCED_SERVER_ASYNC_POOLS == null) {
                P2P_REFERENCED_SERVER_ASYNC_POOLS = new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors()*2, Runtime.getRuntime().availableProcessors() * 2,
                    60L, TimeUnit.MINUTES,
                    new ArrayBlockingQueue<>(4096));
            }

        } catch (Exception e) {
            log.error("", e);
        }
        log.info("###ExecutorService Server Using THREAD_POOLS Created###");
    }

    public synchronized static void releaseP2PServerPools() {
        log.info("###ExecutorService Server Using THREAD_POOLS STOPing###");
        try {
            if (P2P_REFERENCED_SERVER_AWAIT_POOLS != null) {
                P2P_REFERENCED_SERVER_AWAIT_POOLS.shutdown();
                P2P_REFERENCED_SERVER_AWAIT_POOLS = null;
            }
            if (P2P_REFERENCED_SERVER_ASYNC_POOLS != null) {
                P2P_REFERENCED_SERVER_ASYNC_POOLS.shutdown();
                P2P_REFERENCED_SERVER_ASYNC_POOLS = null;
            }
        } catch (Exception e) {
            log.error("", e);
            if (P2P_REFERENCED_SERVER_AWAIT_POOLS != null) {
                P2P_REFERENCED_SERVER_AWAIT_POOLS.shutdownNow();
                P2P_REFERENCED_SERVER_AWAIT_POOLS = null;
            }
            if (P2P_REFERENCED_SERVER_ASYNC_POOLS != null) {
                P2P_REFERENCED_SERVER_ASYNC_POOLS.shutdownNow();
                P2P_REFERENCED_SERVER_ASYNC_POOLS = null;
            }
        }
        log.info("###ExecutorService Server Using THREAD_POOLS STOPED###");
    }
}
