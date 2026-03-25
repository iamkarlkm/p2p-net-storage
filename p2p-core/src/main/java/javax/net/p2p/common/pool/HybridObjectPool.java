package javax.net.p2p.common.pool;

import io.netty.util.Recycler;
import javax.net.p2p.api.P2PCommand;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;


import lombok.extern.slf4j.Slf4j;

/**
 *
 * 混合P2PWrapper对象池
 *
 * 策略：
 *
 * 优先使用Netty Recycler（高性能） Recycler失败时降级到ThreadLocal池 提供详细的监控指标 支持动态切换策略 适用场景：
 *
 * 生产环境 需要高可用性 需要监控和调优
 *
 * @author iamkarl@163.com
 * @param <T>
 */
@Slf4j
public class HybridObjectPool<T extends Recyclable> {
    
       

    /**
     * 对象工厂
     */
    private final Supplier factory;

    

    //==================== 监控指标 ====================
    /**
     * Recycler创建次数
     */
    private static final AtomicLong recyclerCreateCount = new AtomicLong(0);

    /**
     * 备用池创建次数
     */
    private static final AtomicLong fallbackCreateCount = new AtomicLong(0);

    /**
     * 获取次数
     */
    private static final AtomicLong acquireCount = new AtomicLong(0);

    /**
     * 回收次数
     */
    private static final AtomicLong recycleCount = new AtomicLong(0);

    /**
     * 降级次数
     */
    private static final AtomicLong fallbackCount = new AtomicLong(0);

    /**
     * 是否启用降级策略
     */
    private static volatile boolean fallbackEnabled = true;
    
    /**
     *
     * 构造函数
     *
     * @param factory 对象工厂
     * @param maxSize 最大对象数
     */
    public HybridObjectPool(Supplier factory, int maxSize) {
         this.factory = factory;
    }
    
        /**
     * Netty Recycler对象池
     */
    private static final Recycler RECYCLER = new Recycler() {
        @Override
        protected P2PWrapper newObject(Recycler.Handle handle) {
            recyclerCreateCount.incrementAndGet();
            return new P2PWrapper(handle);
        }
    };

    /**
     * ThreadLocal备用对象池
     */
    private static final ThreadLocal<CustomObjectPool> FALLBACK_POOL
        = ThreadLocal.withInitial(() -> new CustomObjectPool<>(() -> {
        fallbackCreateCount.incrementAndGet();
        return new P2PWrapper();
    }, 1024));

    /**
     *
     * 获取P2PWrapper实例
     *
     * @param seq 序列号
     *
     * @param command 命令类型
     *
     * @return P2PWrapper实例
     */
    public static P2PWrapper acquire(int seq, P2PCommand command) {
        acquireCount.incrementAndGet();

        P2PWrapper wrapper = null;

        try {
// 1. 优先使用Netty Recycler
            wrapper = (P2PWrapper) RECYCLER.get();
        } catch (Exception ex) {
            log.warn("Recycler获取对象失败，降级到备用池: {}", ex.getMessage());

            // 2. 降级到ThreadLocal池
            if (fallbackEnabled) {
                fallbackCount.incrementAndGet();
                CustomObjectPool<P2PWrapper> pool = FALLBACK_POOL.get();
                wrapper = pool.acquire();
            }
        }

// 3. 初始化对象
        if (wrapper != null) {
            wrapper.setSeq(seq);
            wrapper.setCommand(command);
            wrapper.setTimestamp(System.currentTimeMillis());
        } else {
            log.error("对象池耗尽，创建临时对象");
            wrapper = new P2PWrapper();
            wrapper.setSeq(seq);
            wrapper.setCommand(command);
            wrapper.setTimestamp(System.currentTimeMillis());
        }

        return wrapper;
    }

    /**
     *
     * 回收P2PWrapper实例
     *
     * @param wrapper 待回收的对象
     */
    public static void recycle(P2PWrapper wrapper) {
        if (wrapper == null) {
            return;
        }

        recycleCount.incrementAndGet();

        try {
// 清理对象状态
            wrapper.clear();

            // 回收到对应的池
            if (wrapper.getHandle() != null) {
                // Recycler对象
                wrapper.recycle();
            } else if (fallbackEnabled) {
                // 备用池对象
                CustomObjectPool<P2PWrapper> pool = FALLBACK_POOL.get();
                pool.release(wrapper);
            }
        } catch (Exception ex) {
            log.error("回收对象失败: {}", ex.getMessage());
        }
    }

    /**
     *
     * 获取监控指标
     *
     * @return 监控指标字符串
     */
    public static String getMetrics() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== P2PWrapper对象池监控 ===\n");
        sb.append(String.format(
            "获取次数: %d", acquireCount.get()));
        sb.append(String.format(
            "回收次数: %d", recycleCount.get()));
        sb.append(String.format(
            "Recycler创建: %d", recyclerCreateCount.get()));
        sb.append(String.format(
            "备用池创建: %d", fallbackCreateCount.get()));
        sb.append(String.format(
            "降级次数: %d", fallbackCount.get()));
        sb.append(String.format("降级率: %.2f%%",
            acquireCount.get() > 0 ? (fallbackCount.get() * 100.0 / acquireCount.get()) : 0
        ));
// 备用池统计
        CustomObjectPool pool = FALLBACK_POOL.get();
        if (pool != null) {
            sb.append(String.format(
                "备用池状态: %s", pool.getStats()));
        }

        return sb.toString();
    }

    /**
     *
     * 启用/禁用降级策略
     *
     * @param enabled true表示启用
     */
    public static void setFallbackEnabled(boolean enabled) {
        fallbackEnabled = enabled;
        log.info("降级策略已{}", enabled ? "启用" : "禁用");
    }

    /**
     *
     * 重置统计指标
     */
    public static void resetMetrics() {
        recyclerCreateCount.set(0);
        fallbackCreateCount.set(0);
        acquireCount.set(0);
        recycleCount.set(0);
        fallbackCount.set(0);
        log.info("监控指标已重置");
    }
}
