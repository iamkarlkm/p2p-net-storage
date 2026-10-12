package ds;

import com.q3lives.ds.database.fulltext.cluster.resilience.*;
import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P32 T2: 4 守卫正确性。
 * TR 总数 6：TR2.1 Timeout 超时强制中断；TR2.2 Retry 指数退避；TR2.3 CB 失败率超阈值 OPEN；
 *         TR2.4 RateLimiter 令牌耗尽拒绝；TR2.5 不可重试异常 0 重试；TR2.6 RateLimitException 不计 CB。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P32_T2_GuardCorrectnessTest {

    // ============== TR2.1 Timeout 超时强制中断 ==============

    @Test(timeout = 30000)
    public void tr2_1_TimeoutGuard_FastCallPasses() throws Exception {
        TimeoutGuard g = new TimeoutGuard(2000);
        Integer r = g.wrap(() -> 42);
        Assert.assertEquals(Integer.valueOf(42), r);
        g.shutdown();
    }

    @Test(timeout = 30000)
    public void tr2_1_TimeoutGuard_ExceedsThrowsQueryTimeout() {
        TimeoutGuard g = new TimeoutGuard(100);
        long start = System.currentTimeMillis();
        try {
            g.wrap(() -> { Thread.sleep(2000); return "should-not-reach"; });
            Assert.fail("expected QueryTimeoutException");
        } catch (QueryTimeoutException expected) {
            long elapsed = System.currentTimeMillis() - start;
            Assert.assertEquals(100L, expected.getTimeoutMs());
            Assert.assertTrue("timeout should fire fast, elapsed=" + elapsed, elapsed < 1500);
        } catch (Throwable t) {
            Assert.fail("expected QueryTimeoutException, got " + t.getClass().getName());
        } finally {
            g.shutdown();
        }
    }

    // ============== TR2.2 Retry 指数退避 ==============

    @Test(timeout = 30000)
    public void tr2_2_RetryGuard_ExponentialBackoff() {
        Set<Class<? extends Throwable>> retryOn = new HashSet<>();
        retryOn.add(IOException.class);
        RetryGuard g = new RetryGuard(3, 50, retryOn);
        AtomicInteger calls = new AtomicInteger(0);
        long start = System.currentTimeMillis();
        try {
            g.wrap(() -> {
                calls.incrementAndGet();
                throw new IOException("boom");
            });
            Assert.fail("expected RetryExhaustedException");
        } catch (RetryExhaustedException expected) {
            Assert.assertEquals(4, expected.getAttempts());
            Assert.assertEquals(4, calls.get());
            long elapsed = System.currentTimeMillis() - start;
            Assert.assertTrue("backoff should >= 250ms, elapsed=" + elapsed, elapsed >= 250);
            Assert.assertTrue("backoff should < 1500ms, elapsed=" + elapsed, elapsed < 1500);
        } catch (Throwable t) {
            Assert.fail("expected RetryExhaustedException, got " + t.getClass().getName());
        }
    }

    // ============== TR2.3 CircuitBreaker 失败率超阈值 OPEN ==============

    @Test(timeout = 30000)
    public void tr2_3_CircuitBreaker_OpensOnFailureRate() {
        CircuitBreakerGuard cb = new CircuitBreakerGuard(0.5, 10, 1000);
        Assert.assertEquals(CircuitBreakerGuard.State.CLOSED, cb.getState());

        for (int i = 0; i < 10; i++) {
            try {
                cb.wrap(() -> { throw new IOException("boom"); });
                Assert.fail("expected IOException on iteration " + i);
            } catch (IOException expected) {
                // OK
            } catch (Throwable t) {
                Assert.fail("expected IOException, got " + t.getClass().getName());
            }
        }
        Assert.assertEquals(CircuitBreakerGuard.State.OPEN, cb.getState());

        try {
            cb.wrap(() -> "should-not-reach");
            Assert.fail("expected CircuitOpenException");
        } catch (CircuitOpenException expected) {
            Assert.assertEquals(1000L, expected.getOpenDurationMs());
        } catch (Throwable t) {
            Assert.fail("expected CircuitOpenException, got " + t.getClass().getName());
        }
    }

    // ============== TR2.4 RateLimiter 令牌耗尽拒绝 ==============

    @Test(timeout = 30000)
    public void tr2_4_RateLimiter_RejectsWhenExhausted() {
        RateLimiterGuard rl = new RateLimiterGuard(100, 5);
        int passed = 0;
        int rejected = 0;
        for (int i = 0; i < 20; i++) {
            try {
                rl.wrap(() -> 1);
                passed++;
            } catch (RateLimitExceededException expected) {
                rejected++;
            } catch (Throwable t) {
                Assert.fail("expected RateLimitExceededException, got " + t.getClass().getName());
            }
        }
        Assert.assertTrue("at least 4 passed, got " + passed, passed >= 4);
        Assert.assertTrue("at least 1 rejected, got " + rejected, rejected >= 1);
    }

    // ============== TR2.5 不可重试异常 0 重试 ==============

    @Test(timeout = 30000)
    public void tr2_5_RetryGuard_NonRetryable_NoRetry() {
        Set<Class<? extends Throwable>> retryOn = new HashSet<>();
        retryOn.add(IOException.class);
        RetryGuard g = new RetryGuard(3, 50, retryOn);
        AtomicInteger calls = new AtomicInteger(0);
        try {
            g.wrap(() -> {
                calls.incrementAndGet();
                throw new IllegalArgumentException("not retryable");
            });
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals(1, calls.get());
        } catch (Throwable t) {
            Assert.fail("expected IllegalArgumentException, got " + t.getClass().getName());
        }
    }

    // ============== TR2.6 RateLimitException 不计 CircuitBreaker 失败 ==============

    @Test(timeout = 30000)
    public void tr2_6_RateLimitException_NotCountedAsCBFailure() {
        CircuitBreakerGuard cb = new CircuitBreakerGuard(0.5, 10, 1000);
        for (int i = 0; i < 10; i++) {
            try {
                cb.wrap(() -> { throw new RateLimitExceededException("rl", 0, 100); });
                Assert.fail("expected RateLimitExceededException on iteration " + i);
            } catch (RateLimitExceededException expected) {
                // OK
            } catch (Throwable t) {
                Assert.fail("expected RateLimitExceededException, got " + t.getClass().getName());
            }
        }
        Assert.assertEquals(CircuitBreakerGuard.State.CLOSED, cb.getState());
        Assert.assertEquals(0.0, cb.currentFailureRate(), 1e-9);
    }
}
