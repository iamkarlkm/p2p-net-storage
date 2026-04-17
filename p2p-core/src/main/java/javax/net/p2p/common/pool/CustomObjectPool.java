package javax.net.p2p.common.pool;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * 自定义高性能对象池 设计特点： 基于ConcurrentLinkedQueue实现无锁队列 使用AtomicInteger跟踪对象数量 支持动态扩容和限流
 * 零GC设计（对象复用） 性能特点： 获取对象：O(1)，~10-30ns 归还对象：O(1)，~10-30ns 无锁竞争，适合高并发 使用示例： //
 * 创建对象池 CustomObjectPool pool = new CustomObjectPool<>(* () -> new
 * P2PWrapper(), 8192 // 最大对象数 ); // 获取对象 P2PWrapper wrapper = pool.acquire();
 * if (wrapper != null) { try { // 使用对象 wrapper.setSeq(1);
 * wrapper.setCommand(P2PCommand.HEART_PING); } finally {
 *
 * // 归还对象 pool.release(wrapper); } }
 *
 * // 查看统计
 *
 * System.out.println(pool.getStats());
 *
 * @param 对象类型，必须实现Recyclable接口
 *
 * @author iamkarl@163.com
 */
@Slf4j
public class CustomObjectPool<T extends Recyclable> {

    /**
     * 对象队列（无锁） / private final ConcurrentLinkedQueue queue; /* 对象工厂
     */
    private final Supplier factory;

    /**
     * 最大对象数
     */
    private final int maxSize;

    /**
     * 当前对象总数（包括使用中和空闲）
     */
    private final AtomicInteger totalCount;

    /**
     * 空闲对象数
     */
    private final AtomicInteger idleCount;

    /**
     * 统计：获取次数
     */
    private final AtomicInteger borrowCount;

    /**
     * 统计：归还次数
     */
    private final AtomicInteger returnCount;

    /**
     * 统计：创建次数
     */
    private final AtomicInteger createCount;
    
    private final AtomicInteger activeCount;

    /**
     * 统计：获取失败次数（对象池已满）
     */
    private final AtomicInteger acquireFailCount;
    private ConcurrentLinkedQueue<Object> queue;

    /**
     *
     * 构造函数
     *
     * @param factory 对象工厂，用于创建新对象
     *
     * @param maxSize 最大对象数，超过此数量将拒绝创建新对象
     */
    public CustomObjectPool(Supplier factory, int maxSize) {
        if (factory == null) {
            throw new IllegalArgumentException("factory不能为null");
        }
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize必须大于0");
        }

        this.queue = new ConcurrentLinkedQueue<>();
        this.factory = factory;
        this.maxSize = maxSize;
        this.totalCount = new AtomicInteger(0);
        this.activeCount = new AtomicInteger(0);
        this.idleCount = new AtomicInteger(0);
        this.borrowCount = new AtomicInteger(0);
        this.returnCount = new AtomicInteger(0);
        this.createCount = new AtomicInteger(0);
        this.acquireFailCount = new AtomicInteger(0);
        log.info("对象池初始化: maxSize={}, factory={}", maxSize, factory.getClass().getSimpleName());
    }

    /**
     *
     * 从对象池获取对象
     *
     * 逻辑：
     *
     * 尝试从队列获取空闲对象 如果队列为空且未达上限，创建新对象 如果达到上限，返回null（调用方需处理）
     *
     * @return 对象实例，如果对象池已满则返回null
     */
    public T acquire() {
        borrowCount.incrementAndGet();

// 1. 尝试从队列获取空闲对象
        T obj = (T) queue.poll();
        if (obj != null) {
            idleCount.decrementAndGet();

            if (log.isTraceEnabled()) {
                log.trace("从对象池获取对象: 空闲={}, 总数={}", idleCount.get(), totalCount.get());
            }

            return obj;
        }

// 2. 队列为空，尝试创建新对象
        int current = totalCount.get();
        if (current < maxSize) {
// CAS操作，确保不超过最大值
            if (totalCount.compareAndSet(current, current + 1)) {
                try {
                    obj = (T) factory.get();
                    createCount.incrementAndGet();

                    if (log.isDebugEnabled()) {
                        log.debug("创建新对象: 当前总数={}/{}", current + 1, maxSize);
                    }

                    return obj;

                } catch (Exception ex) {
                    // 创建失败，回滚计数
                    totalCount.decrementAndGet();
                    log.error("创建对象失败: {}", ex.getMessage(), ex);
                    return null;
                }
            }
        }

// 3. 达到上限，返回null
        acquireFailCount.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("对象池已满: {}/{}, 获取失败次数={}",
                current, maxSize, acquireFailCount.get());
        }

        return null;
    }

    /**
     *
     * 归还对象到对象池
     *
     * 逻辑：
     *
     * 验证对象有效性 清理对象状态 放回队列 更新统计
     *
     * @param obj 待归还的对象
     *
     * @return true表示成功归还，false表示归还失败
     */
    public boolean release(T obj) {
        if (obj == null) {
            log.warn("尝试归还null对象");
            return false;
        }

        returnCount.incrementAndGet();

        try {
// 清理对象状态
            obj.clear();

            // 放回队列
            boolean offered = queue.offer(obj);
            if (offered) {
                idleCount.incrementAndGet();

                if (log.isTraceEnabled()) {
                    log.trace("对象已归还: 空闲={}, 总数={}", idleCount.get(), totalCount.get());
                }

                return true;
            } else {
                log.error("归还对象失败: 队列offer失败");
                return false;
            }
        } catch (Exception ex) {
            log.error("归还对象失败: {}", ex.getMessage(), ex);
            return false;
        }
    }

    /**
     *
     * 获取对象池统计信息
     *
     * @return 统计信息对象
     */
    public PoolStats getStats() {
        return new PoolStats(totalCount.get(), idleCount.get(), totalCount.get() - idleCount.get(), borrowCount.get(), returnCount.get(), createCount.get(), acquireFailCount.get());
    }

    /**
     *
     * 清空对象池
     *
     * 注意：
     *
     * 只清空闲对象 使用中的对象不受影响 重置对象计数
     */
    public void clear() {
        T obj;
        int cleared = 0;
        while ((obj = (T) queue.poll()) != null) {
            try {
                obj.clear();
                cleared++;
            } catch (Exception ex) {
                log.error("清理对象失败: {}", ex.getMessage());
            }
        }

// 重置计数
        totalCount.set(totalCount.get() - cleared);
        idleCount.set(0);

        log.info("对象池已清空: 清理了{}个对象,剩余活跃对象={}", cleared, totalCount.get());
    }

    /**
     *
     * 获取对象池容量信息
     *
     * @return 容量信息字符串
     */
    public String getCapacityInfo() {
        int total = totalCount.get();
        int idle = idleCount.get();
        int active = total - idle;

        return String.format("容量: %d/%d (活跃=%d, 空闲=%d, 利用率=%.1f%%)",
            total, maxSize, active, idle,
            total > 0 ? (active * 100.0 / total) : 0);
    }

    /**
     *
     * 预热对象池
     *
     * 预先创建指定数量的对象，避免首次使用时的延迟
     *
     * @param count 预热对象数量
     *
     * @return 实际创建的对象数量
     */
    public int warmUp(int count) {
        if (count <= 0) {
            return 0;
        }

        log.info("开始预热对象池: 目标数量={}", count);

        int created = 0;
        T[] objects = (T[]) new Recyclable[count];

// 创建对象
        for (int i = 0; i < count; i++) {
            T obj = acquire();
            if (obj != null) {
                objects[i] = obj;
                created++;
            } else {
                break;
            }
        }

// 归还对象
        for (int i = 0; i < created; i++) {
            release(objects[i]);
        }

        log.info("对象池预热完成: 创建了{}个对象, {}", created, getCapacityInfo());

        return created;
    }

    /**
     *
     * 获取最大容量
     *
     * @return 最大对象数
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     *
     * 获取当前总对象数
     *
     * @return 总对象数（包括使用中和空闲）
     */
    public int getTotalCount() {
        return totalCount.get();
    }

    /**
     *
     * 获取空闲对象数
     *
     * @return 空闲对象数
     */
    public int getIdleCount() {
        return idleCount.get();
    }

    /**
     *
     * 获取活跃对象数
     *
     * @return 活跃对象数（使用中）
     */
    public int getActiveCount() {
        return totalCount.get() - idleCount.get();
    }
    


    /**
     *
     * 计算命中率（从池中获取的比例）
     *
     * @return 命中率（0-100）
     */
    public double getHitRate() {
        if (borrowCount.get() == 0) {
            return 0.0;
        }
        return (borrowCount.get() - createCount.get()) * 100.0 / borrowCount.get();
    }

    /**
     *
     * 计算利用率（活跃对象占总对象的比例）
     *
     * @return 利用率（0-100）
     */
    public double getUtilization() {
        if (totalCount.get() == 0) {
            return 0.0;
        }
        return activeCount.get() * 100.0 / totalCount.get();
    }

    /**
     *
     * 计算失败率
     *
     * @return 失败率（0-100）
     */
    public double getFailRate() {
        if (borrowCount.get() == 0) {
            return 0.0;
        }
        return acquireFailCount.get() * 100.0 / borrowCount.get();
    }

    @Override
    public String toString() {
        return String.format(
            "PoolStats{total=%d, idle=%d, active=%d, "
            + "borrow=%d, return=%d, create=%d, fail=%d, "
            + "hitRate=%.2f%%, utilization=%.2f%%, failRate=%.2f%%}",
            totalCount, idleCount, activeCount,
            borrowCount, returnCount, createCount, acquireFailCount,
            getHitRate(), getUtilization(), getFailRate()
        );
    }

    /**
     *
     * 生成详细报告
     *
     * @return 详细报告字符串
     */
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 对象池统计 === ");
        sb.append(String.format("总对象数: %d ", totalCount));
        sb.append(String.format("空闲对象: %d ", idleCount));
        sb.append(String.format("活跃对象: %d ", activeCount));
        sb.append(String.format("获取次数: %d ", borrowCount));
        sb.append(String.format("归还次数: %d ", returnCount));
        sb.append(String.format("创建次数: %d ", createCount));
        sb.append(String.format("失败次数: %d ", acquireFailCount));
        sb.append(String.format("命中率: %.2f%% ", getHitRate()));
        sb.append(String.format("利用率: %.2f%% ", getUtilization()));
        sb.append(String.format("失败率: %.2f%% ", getFailRate()));
        return sb.toString();
    }

    public static class PoolStats {

        public short borrowCount;
        public short returnCount;
        public int createCount;
        public int totalCount;
        public int idleCount;
        public int acquireFailCount;

        public PoolStats(int get, int get1, int par, int get2, int get3, int get4, int get5) {
        }

        public double getHitRate() {
            throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
        }

        public Object toDetailedString() {
            throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
        }
    }
} 
