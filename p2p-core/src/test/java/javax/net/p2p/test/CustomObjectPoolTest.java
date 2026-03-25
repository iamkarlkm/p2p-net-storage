package javax.net.p2p.test;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.pool.CustomObjectPool;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.common.pool.P2PWrapper;

/**
 *
 * CustomObjectPool单元测试
 *
 * @author iamkarl@163.com
 */
@Slf4j
public class CustomObjectPoolTest {

    private CustomObjectPool<P2PWrapper> pool;
    private static final int POOL_SIZE = 100;

    @BeforeEach
    public void setUp() {
        pool = new CustomObjectPool<>(() -> new P2PWrapper(), POOL_SIZE);
        log.info("测试开始: {}", pool.getCapacityInfo());
    }

    @AfterEach
    public void tearDown() {
        log.info("测试结束: {}", pool.getStats().toDetailedString());
        pool.clear();
    }

    /**
     *
     * 测试1：基本获取和归还
     */
    @Test
    public void testBasicAcquireAndRelease() {
        log.info("=== 测试1: 基本获取和归还 ===");

// 获取对象
        P2PWrapper wrapper = pool.acquire();
        assertNotNull(wrapper, "应该成功获取对象");

// 初始化对象
        wrapper.setSeq(1);
        wrapper.setCommand(P2PCommand.HEART_PING);
        assertEquals(1, pool.getActiveCount(), "活跃对象数应为1");
        assertEquals(0, pool.getIdleCount(), "空闲对象数应为0");

// 归还对象
        boolean released = pool.release(wrapper);
        assertTrue(released, "应该成功归还对象");

        assertEquals(0, pool.getActiveCount(), "活跃对象数应为0");
        assertEquals(1, pool.getIdleCount(), "空闲对象数应为1");

// 验证对象已清理
        assertEquals(0, wrapper.getSeq(), "seq应该被清理");
        assertNull(wrapper.getCommand(), "command应该被清理");
    }

    /**
     *
     * 测试2：对象复用
     */
    @Test
    public void testObjectReuse() {
        log.info("=== 测试2: 对象复用 ===");

// 第一次获取
        P2PWrapper wrapper1 = pool.acquire();
        wrapper1.setSeq(100);
        pool.release(wrapper1);

// 第二次获取（应该是同一个对象）
        P2PWrapper wrapper2 = pool.acquire();

        assertSame(wrapper1, wrapper2, "应该复用同一个对象");
        assertEquals(0, wrapper2.getSeq(), "对象应该已被清理");
        assertEquals(1, pool.getStats().createCount, "只应该创建1个对象");
    }

    /**
     *
     * 测试3：对象池容量限制
     */
    @Test
    public void testPoolCapacityLimit() {
        log.info("=== 测试3: 对象池容量限制 ===");

        List<P2PWrapper> wrappers = new ArrayList<>();

// 获取到容量上限
        for (int i = 0; i < POOL_SIZE; i++) {
            P2PWrapper wrapper = pool.acquire();
            assertNotNull(wrapper, "应该成功获取对象 " + i);
            wrappers.add(wrapper);
        }

        assertEquals(POOL_SIZE, pool.getActiveCount(), "活跃对象数应等于容量");

// 尝试超过容量
        P2PWrapper extraWrapper = pool.acquire();
        assertNull(extraWrapper, "超过容量应该返回null");

        assertTrue(pool.getStats().acquireFailCount > 0, "应该有获取失败记录");

// 归还所有对象
        for (P2PWrapper wrapper : wrappers) {
            pool.release(wrapper);
        }

        assertEquals(POOL_SIZE, pool.getIdleCount(), "所有对象应该空闲");
    }

    /**
     *
     * 测试4：并发获取和归还
     */
    @Test
    public void testConcurrentAcquireAndRelease() throws InterruptedException {
        log.info("=== 测试4: 并发获取和归还 ===");

        int threadCount = 10;
        int iterationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        P2PWrapper wrapper = pool.acquire();
                        if (wrapper != null) {
                            wrapper.setSeq(threadId * iterationsPerThread + j);
                            wrapper.setCommand(P2PCommand.DATA_TRANSFER);

                            // 模拟使用
                            Thread.sleep(0, 100);

                            pool.release(wrapper);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        CustomObjectPool.PoolStats stats = pool.getStats();
        log.info("并发测试统计: {}", stats);

        assertEquals(threadCount * iterationsPerThread, stats.borrowCount, "获取次数应该正确");
        assertTrue(stats.returnCount > 0, "应该有归还记录");
        assertTrue(stats.getHitRate() > 90, "命中率应该很高");
    }

    /**
     *
     * 测试5：预热功能
     */
    @Test
    public void testWarmUp() {
        log.info("=== 测试5: 预热功能 ===");

        int warmUpCount = 50;
        int created = pool.warmUp(warmUpCount);

        assertEquals(warmUpCount, created, "应该创建指定数量的对象");
        assertEquals(warmUpCount, pool.getIdleCount(), "所有对象应该空闲");
        assertEquals(warmUpCount, pool.getTotalCount(), "总对象数应该正确");
    }

    /**
     *
     * 测试6：清空对象池
     */
    @Test
    public void testClear() {
        log.info("=== 测试6: 清空对象池 ===");

// 预热
        pool.warmUp(20);
        assertEquals(20, pool.getIdleCount(), "应该有20个空闲对象");

// 获取一些对象（不归还）
        List<P2PWrapper> active = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            active.add(pool.acquire());
        }

        assertEquals(15, pool.getIdleCount(), "应该有15个空闲对象");
        assertEquals(5, pool.getActiveCount(), "应该有5个活跃对象");

// 清空
        pool.clear();

        assertEquals(0, pool.getIdleCount(), "空闲对象应该被清空");
        assertEquals(5, pool.getTotalCount(), "活跃对象仍然存在");

// 归还活跃对象
        for (P2PWrapper wrapper : active) {
            pool.release(wrapper);
        }

        assertEquals(5, pool.getIdleCount(), "归还后应该有5个空闲对象");
    }

    /**
     *
     * 测试7：统计信息准确性
     */
    @Test
    public void testStatistics() {
        log.info("=== 测试7: 统计信息准确性 ===");

// 执行一系列操作
        for (int i = 0; i < 10; i++) {
            P2PWrapper wrapper = pool.acquire();
            if (wrapper != null) {
                pool.release(wrapper);
            }
        }

        CustomObjectPool.PoolStats stats = pool.getStats();

        assertEquals(10, stats.borrowCount, "获取次数应为10");
        assertEquals(10, stats.returnCount, "归还次数应为10");
        assertTrue(stats.createCount > 0, "应该有创建记录");
        assertTrue(stats.createCount <= 10, "创建次数不应超过获取次数");

        double hitRate = stats.getHitRate();
        assertTrue(hitRate >= 0 && hitRate <= 100, "命中率应在0-100之间");

        log.info("统计信息: {}", stats.toDetailedString());
    }

    /**
     *
     * 测试8：异常处理
     */
    @Test
    public void testExceptionHandling() {
        log.info("=== 测试8: 异常处理 ===");

// 测试归还null
        boolean result = pool.release(null);
        assertFalse(result, "归还null应该返回false");

// 测试重复归还
        P2PWrapper wrapper = pool.acquire();
        assertNotNull(wrapper);
        pool.release(wrapper);
        pool.release(wrapper); // 重复归还

// 对象池应该仍然正常工作
        P2PWrapper wrapper2 = pool.acquire();
        assertNotNull(wrapper2, "对象池应该仍然可用");
        pool.release(wrapper2);
    }

    /**
     *
     * 测试9：性能基准
     */
    @Test
    public void testPerformanceBenchmark() {
        log.info("=== 测试9: 性能基准 ===");

        int iterations = 100000;

// 预热
        pool.warmUp(50);

// 测试对象池
        long start1 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            P2PWrapper wrapper = pool.acquire();
            if (wrapper != null) {
                wrapper.setSeq(i);
                pool.release(wrapper);
            }
        }
        long duration1 = System.nanoTime() - start1;

// 测试直接创建
        long start2 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            P2PWrapper wrapper = new P2PWrapper();
            wrapper.setSeq(i);
// 等待GC
        }
        long duration2 = System.nanoTime() - start2;

        log.info("对象池: {} ns/op, 总计{} ms", duration1 / iterations, duration1 / 1_000_000);
        log.info("直接创建: {} ns/op, 总计 {} ms", duration2 / iterations, duration2 / 1_000_000);
        log.info("性能提升: {:.2f}x", (double) duration2 / duration1);

        assertTrue(duration1 < duration2, "对象池应该比直接创建更快");
    }

    /**
     *
     * 测试10：内存泄漏检测
     */
    @Test
    public void testMemoryLeak() {
        log.info("=== 测试10: 内存泄漏检测 ===");

// 大量获取和归还
        for (int i = 0; i < 10000; i++) {
            P2PWrapper wrapper = pool.acquire();
            if (wrapper != null) {
                wrapper.setSeq(i);
                wrapper.setData(new byte[1024]); // 1KB数据
                pool.release(wrapper);
            }
        }

        CustomObjectPool.PoolStats stats = pool.getStats();

// 验证对象数量没有无限增长
        assertTrue(stats.totalCount <= POOL_SIZE, "对象总数不应超过池容量");

// 验证大部分对象都是空闲的
        assertTrue(stats.idleCount > 0, "应该有空闲对象");

        log.info("内存泄漏检测完成: {}", stats);
    }
}
