package javax.net.p2p.benchmark;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.pool.CustomObjectPool;
import javax.net.p2p.common.pool.HybridObjectPool;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.common.pool.HybridObjectPool;
import javax.net.p2p.common.pool.P2PWrapper;

/**
 *
 * 对象池性能基准测试
 *
 * 测试场景：
 *
 * 单线程性能测试 多线程并发测试 内存占用测试 GC影响测试
 *
 * @author iamkarl@163.com
 */
@Slf4j
public class ObjectPoolBenchmark {

    private static final int ITERATIONS = 1_000_000;
    private static final int THREAD_COUNT = 100;
    private static final CustomObjectPool<P2PWrapper> CUSTOM_POOL
        = new CustomObjectPool<>(() -> new P2PWrapper(), 8192);

    /**
     *
     * 测试1：直接创建对象
     */
    public static BenchmarkResult testDirectCreation() {
        log.info("开始测试: 直接创建对象");

        System.gc();
        long memBefore = getUsedMemory();
        long gcBefore = getGCCount();

        long start = System.nanoTime();

        for (int i = 0; i < ITERATIONS; i++) {
            P2PWrapper wrapper = new P2PWrapper();
            wrapper.setSeq(i);
            wrapper.setCommand(P2PCommand.HEART_PING);
            wrapper.setTimestamp(System.currentTimeMillis());
// 让GC回收
        }

        long duration = System.nanoTime() - start;

        System.gc();
        long memAfter = getUsedMemory();
        long gcAfter = getGCCount();

        return new BenchmarkResult(
            "直接创建",
            duration,
            ITERATIONS,
            memAfter - memBefore,
            gcAfter - gcBefore
        );
    }

    /**
     *
     * 测试2：自定义对象池
     */
    public static BenchmarkResult testCustomPool() {
        log.info("开始测试: 自定义对象池");

// 预热
        for (int i = 0; i < 10000; i++) {
            P2PWrapper wrapper = CUSTOM_POOL.acquire();
            if (wrapper != null) {
                CUSTOM_POOL.release(wrapper);
            }
        }

        System.gc();
        long memBefore = getUsedMemory();
        long gcBefore = getGCCount();

        long start = System.nanoTime();

        for (int i = 0; i < ITERATIONS; i++) {
            P2PWrapper wrapper = CUSTOM_POOL.acquire();
            if (wrapper != null) {
                wrapper.setSeq(i);
                wrapper.setCommand(P2PCommand.HEART_PING);
                wrapper.setTimestamp(System.currentTimeMillis());
                CUSTOM_POOL.release(wrapper);
            }
        }

        long duration = System.nanoTime() - start;

        System.gc();
        long memAfter = getUsedMemory();
        long gcAfter = getGCCount();

        return new BenchmarkResult(
            "自定义对象池",
            duration,
            ITERATIONS,
            memAfter - memBefore,
            gcAfter - gcBefore
        );
    }

    /**
     *
     * 测试3：混合对象池
     */
    public static BenchmarkResult testHybridPool() {
        log.info("开始测试: 混合对象池");

// 预热
        for (int i = 0; i < 10000; i++) {
            P2PWrapper wrapper = HybridObjectPool.acquire(i, P2PCommand.HEART_PING);
            HybridObjectPool.recycle(wrapper);
        }

        System.gc();
        long memBefore = getUsedMemory();
        long gcBefore = getGCCount();

        long start = System.nanoTime();

        for (int i = 0; i < ITERATIONS; i++) {
            P2PWrapper wrapper = HybridObjectPool.acquire(i, P2PCommand.HEART_PING);
            wrapper.setTimestamp(System.currentTimeMillis());
            HybridObjectPool.recycle(wrapper);
        }

        long duration = System.nanoTime() - start;

        System.gc();
        long memAfter = getUsedMemory();
        long gcAfter = getGCCount();

        return new BenchmarkResult(
            "混合对象池",
            duration,
            ITERATIONS,
            memAfter - memBefore,
            gcAfter - gcBefore
        );
    }

    /**
     *
     * 测试4：并发性能测试
     */
    public static BenchmarkResult testConcurrentCustomPool() throws InterruptedException {
        log.info("开始测试: 并发自定义对象池");

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        int iterationsPerThread = ITERATIONS / THREAD_COUNT;

        System.gc();
        long memBefore = getUsedMemory();
        long gcBefore = getGCCount();

        long start = System.nanoTime();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        P2PWrapper wrapper = CUSTOM_POOL.acquire();
                        if (wrapper != null) {
                            wrapper.setSeq(j);
                            wrapper.setCommand(P2PCommand.CHAT_MESSAGE);
                            CUSTOM_POOL.release(wrapper);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long duration = System.nanoTime() - start;

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        System.gc();
        long memAfter = getUsedMemory();
        long gcAfter = getGCCount();

        return new BenchmarkResult(
            "并发自定义池",
            duration,
            ITERATIONS,
            memAfter - memBefore,
            gcAfter - gcBefore
        );
    }

    /**
     *
     * 获取已使用内存（字节）
     */
    private static long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     *
     * 获取GC次数
     */
    private static long getGCCount() {
        long count = 0;
        for (java.lang.management.GarbageCollectorMXBean gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            count += gc.getCollectionCount();
        }
        return count;
    }

    /**
     *
     * 基准测试结果
     */
    static class BenchmarkResult {

        final String name;
        final long durationNs;
        final int iterations;
        final long memoryUsed;
        final long gcCount;

        BenchmarkResult(String name, long durationNs, int iterations, long memoryUsed, long gcCount) {
            this.name = name;
            this.durationNs = durationNs;
            this.iterations = iterations;
            this.memoryUsed = memoryUsed;
            this.gcCount = gcCount;
        }

        void print() {
            log.info("=== {} ===", name);
            log.info("总耗时: {} ms", durationNs / 1_000_000);
            log.info("平均耗时: {} ns/op", durationNs / iterations);
            log.info("吞吐量: {} ops/s", (iterations * 1_000_000_000L) / durationNs);
            log.info("内存占用: {} MB", memoryUsed / (1024 * 1024));
            log.info("GC次数: {}", gcCount);
        }
    }

    /**
     *
     * 主方法：运行所有基准测试
     */
    public static void main(String[] args) throws InterruptedException {
        log.info("=== 对象池性能基准测试 ===");
        log.info("迭代次数: {}", ITERATIONS);
        log.info("并发线程: {}\n", THREAD_COUNT);

// 测试1
        BenchmarkResult result1 = testDirectCreation();
        result1.print();
        log.info("");

// 测试2
        BenchmarkResult result2 = testCustomPool();
        result2.print();
        log.info("");

// 测试3
        BenchmarkResult result3 = testHybridPool();
        result3.print();
        log.info("");

// 测试4
        BenchmarkResult result4 = testConcurrentCustomPool();
        result4.print();
        log.info("");

// 对比分析
        log.info("=== 性能对比 ===");
        printComparison("自定义对象池 vs 直接创建", result2, result1);
        printComparison("混合对象池 vs 直接创建", result3, result1);
        printComparison("混合对象池 vs 自定义池", result3, result2);

// 输出对象池状态
        log.info("=== 对象池状态 ===");
        log.info("自定义池: {}", CUSTOM_POOL.getStats());
        log.info("混合池:\n{}", HybridObjectPool.getMetrics());
    }

    private static void printComparison(String title, BenchmarkResult a, BenchmarkResult b) {
        double speedup = (double) b.durationNs / a.durationNs;
        double memReduction = (1.0 - (double) a.memoryUsed / b.memoryUsed) * 100;
        double gcReduction = (1.0 - (double) a.gcCount / b.gcCount) * 100;

        log.info("\n{}", title);
        log.info("性能提升: {:.2f}x ({:.1f}%)", speedup, (speedup - 1) * 100);
        log.info("  内存减少: {:.1f}%", memReduction);
        log.info("  GC减少: {:.1f}%", gcReduction);
    }
}
