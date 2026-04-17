package javax.net.p2p.example;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.pool.CustomObjectPool;
import javax.net.p2p.common.pool.HybridObjectPool;
import javax.net.p2p.common.pool.P2PWrapper;
import javax.net.p2p.common.pool.SegmentedObjectPool;
import javax.net.p2p.common.pool.ThreadLocalObjectPool;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * 对象池使用示例
 *
 * @author iamkarl@163.com
 */
@Slf4j
public class ObjectPoolUsageExample {

    // 1. 通用场景：分段池（推荐）
    SegmentedObjectPool<P2PWrapper> pool0
        = new SegmentedObjectPool<>(() -> new P2PWrapper(), 8192);

// 2. 极致性能 + 内存充足：混合池
    HybridObjectPool<P2PWrapper> pool2 = new HybridObjectPool<>(() -> new P2PWrapper(), 8192);

// 3. 简单场景：全局池
    CustomObjectPool<P2PWrapper> pool3
        = new CustomObjectPool<>(() -> new P2PWrapper(), 8192);

// 4. 特殊场景：ThreadLocal池（谨慎使用）
//仅当满足所有以下条件时：
// - 线程数 < 20
// - 负载均衡
// - 不跨线程
// - 内存充足
    ThreadLocalObjectPool<P2PWrapper> pool
        = new ThreadLocalObjectPool<>(() -> new P2PWrapper(), 64);

    /**
     *
     * 示例1：基本使用
     */
    public void basicUsage() {
// 1. 从对象池获取对象
        P2PWrapper request = HybridObjectPool.acquire(1001, P2PCommand.HEART_PING);
        request.setData("Hello, P2P!");

        try {
// 2. 使用对象
            processMessage(request);

        } finally {
// 3. 使用完毕后回收（重要！）
            HybridObjectPool.recycle(request);
        }
    }

    /**
     *
     * 示例2：批量处理
     */
    public void batchProcessing() {
        for (int i = 0; i < 10000; i++) {
            P2PWrapper wrapper = null;
            try {
// 获取对象
                wrapper = HybridObjectPool.acquire(i, P2PCommand.DATA_TRANSFER);
                wrapper.setData("Data-" + i);

                // 处理
                processMessage(wrapper);

            } finally {
                // 确保回收
                if (wrapper != null) {
                    HybridObjectPool.recycle(wrapper);
                }
            }
        }

// 输出监控指标
        log.info("批量处理完成:\n{}", HybridObjectPool.getMetrics());
    }

    /**
     *
     * 示例3：异常安全处理
     */
    public void exceptionSafeUsage() {
        P2PWrapper wrapper = HybridObjectPool.acquire(2001, P2PCommand.FORCE_PUT_FILE);

        try {
            wrapper.setData(loadLargeFile());

            // 可能抛出异常的操作
            sendToRemote(wrapper);
        } catch (Exception ex) {
            log.error("处理失败: {}", ex.getMessage(), ex);

        } finally {
// 无论是否异常，都要回收
            HybridObjectPool.recycle(wrapper);
        }
    }

    /**
     *
     * 示例4：Try-with-resources模式（推荐）
     */
    public void tryWithResourcesUsage() {
// 如果P2PWrapper实现了AutoCloseable接口
        try (PooledP2PWrapper wrapper = PooledP2PWrapper.acquire(3001, P2PCommand.ECHO)) {
            wrapper.setData("SELECT * FROM users");

            // 使用对象
            processMessage(wrapper.get());

            // 自动回收，无需手动调用recycle
        }
    }

    /**
     *
     * 示例5：性能测试
     */
    public void performanceTest() {
        int iterations = 1000000;

// 测试1：使用对象池
        long start1 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            P2PWrapper wrapper = HybridObjectPool.acquire(i, P2PCommand.HEART_PING);
            HybridObjectPool.recycle(wrapper);
        }
        long duration1 = System.nanoTime() - start1;

// 测试2：直接创建对象
        long start2 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            P2PWrapper wrapper = new P2PWrapper();
            wrapper.setSeq(i);
            wrapper.setCommand(P2PCommand.HEART_PING);
// 不回收，等待GC
        }
        long duration2 = System.nanoTime() - start2;

        log.info("性能对比 ({} 次迭代):", iterations);
        log.info("对象池: {} ns/op ({} ms总计)", duration1 / iterations, duration1 / 1_000_000);
        log.info("直接创建: {} ns/op ({} ms 总计)", duration2 / iterations, duration2 / 1_000_000);
        log.info("性能提升: {:.2f}%", ((duration2 - duration1) * 100.0 / duration2));

// 输出对象池指标
        log.info(
            "{}", HybridObjectPool.getMetrics());
    }

    /**
     *
     * 示例6：监控和调优
     */
    public void monitoringExample() {
// 运行一段时间后
        String metrics = HybridObjectPool.getMetrics();
        log.info(
            "对象池监控:{}", metrics);

// 根据监控结果调整策略
// 如果降级率过高，可以禁用降级
// HybridObjectPool.setFallbackEnabled(false);
// 重置指标，开始新的监控周期
        HybridObjectPool.resetMetrics();
    }

//==================== 辅助方法 ====================
    private void processMessage(P2PWrapper wrapper) {
// 模拟消息处理
        log.debug("处理消息: seq={}, command={}", wrapper.getSeq(), wrapper.getCommand());
    }

    private Object loadLargeFile() {
// 模拟加载大文件
        return new byte[1024 * 1024]; // 1MB
    }

    private void sendToRemote(P2PWrapper wrapper) {
// 模拟发送到远程
        log.debug("发送到远程: {}", wrapper);
    }

    /**
     *
     * 包装类，支持Try-with-resources
     */
    static class PooledP2PWrapper implements AutoCloseable {

        private final P2PWrapper wrapper;

        private PooledP2PWrapper(P2PWrapper wrapper) {
            this.wrapper = wrapper;
        }

        public static PooledP2PWrapper acquire(int seq, P2PCommand command) {
            P2PWrapper wrapper = HybridObjectPool.acquire(seq, command);
            return new PooledP2PWrapper(wrapper);
        }

        public P2PWrapper get() {
            return wrapper;
        }

        public void setData(Object data) {
            wrapper.setData(data);
        }

        @Override
        public void close() {
            HybridObjectPool.recycle(wrapper);
        }
    }
}
