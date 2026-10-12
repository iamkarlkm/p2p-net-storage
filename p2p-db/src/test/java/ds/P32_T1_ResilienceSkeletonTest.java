package ds;

import com.q3lives.ds.database.fulltext.cluster.resilience.*;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * P32 T1: ResilienceConfig + 4 Guard 骨架 + 4 异常 + 状态可观测。
 * TR 总数 4：TR1.1 ResilienceConfig 4 段 IAE；TR1.2 4 Guard 构造 NPE/IAE；TR1.3 4 异常类型；TR1.4 状态可观测。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P32_T1_ResilienceSkeletonTest {

    @Before
    public void setUp() {
        // 不依赖 DB，但保留 @Before 钩子以对齐 P22-P31 模板
    }

    // ============== TR1.1 ResilienceConfig 4 段 IAE ==============

    @Test(timeout = 30000)
    public void tr1_1_ResilienceConfig_Defaults() {
        ResilienceConfig cfg = ResilienceConfig.defaults();
        Assert.assertEquals(30_000L, cfg.getTimeoutMs());
        Assert.assertEquals(2, cfg.getMaxRetries());
        Assert.assertEquals(100L, cfg.getBaseDelayMs());
        Assert.assertEquals(0.5, cfg.getFailureRateThreshold(), 1e-9);
        Assert.assertEquals(50, cfg.getSlidingWindowSize());
        Assert.assertEquals(5_000L, cfg.getOpenDurationMs());
        Assert.assertEquals(100, cfg.getQps());
        Assert.assertEquals(200, cfg.getBurst());
    }

    @Test(timeout = 30000)
    public void tr1_1_ResilienceConfig_AllFieldsIAE() {
        try {
            ResilienceConfig.builder().timeoutMs(0).build();
            Assert.fail("expected IAE for timeoutMs=0");
        } catch (IllegalArgumentException expected) { /* OK */ }
        try {
            ResilienceConfig.builder().maxRetries(-1).build();
            Assert.fail("expected IAE for maxRetries=-1");
        } catch (IllegalArgumentException expected) { /* OK */ }
        try {
            ResilienceConfig.builder().baseDelayMs(0).build();
            Assert.fail("expected IAE for baseDelayMs=0");
        } catch (IllegalArgumentException expected) { /* OK */ }
        try {
            ResilienceConfig.builder().failureRateThreshold(1.5).build();
            Assert.fail("expected IAE for failureRateThreshold=1.5");
        } catch (IllegalArgumentException expected) { /* OK */ }
        try {
            ResilienceConfig.builder().slidingWindowSize(0).build();
            Assert.fail("expected IAE for slidingWindowSize=0");
        } catch (IllegalArgumentException expected) { /* OK */ }
        try {
            ResilienceConfig.builder().openDurationMs(0).build();
            Assert.fail("expected IAE for openDurationMs=0");
        } catch (IllegalArgumentException expected) { /* OK */ }
        try {
            ResilienceConfig.builder().qps(0).build();
            Assert.fail("expected IAE for qps=0");
        } catch (IllegalArgumentException expected) { /* OK */ }
        try {
            ResilienceConfig.builder().burst(0).build();
            Assert.fail("expected IAE for burst=0");
        } catch (IllegalArgumentException expected) { /* OK */ }
    }

    // ============== TR1.2 4 Guard 构造 NPE/IAE ==============

    @Test(timeout = 30000)
    public void tr1_2_TimeoutGuard_CtorIAE() {
        try { new TimeoutGuard(0); Assert.fail("expected IAE"); }
        catch (IllegalArgumentException expected) { /* OK */ }
        TimeoutGuard g = new TimeoutGuard(1000);
        Assert.assertEquals(1000L, g.getTimeoutMs());
        Assert.assertEquals(0L, g.getActiveCallCount());
        g.shutdown();
    }

    @Test(timeout = 30000)
    public void tr1_2_RetryGuard_CtorIAE_NPE() {
        try { new RetryGuard(-1, 100, RetryGuard.defaultRetryOn()); Assert.fail("expected IAE"); }
        catch (IllegalArgumentException expected) { /* OK */ }
        try { new RetryGuard(2, 0, RetryGuard.defaultRetryOn()); Assert.fail("expected IAE"); }
        catch (IllegalArgumentException expected) { /* OK */ }
        try { new RetryGuard(2, 100, null); Assert.fail("expected NPE"); }
        catch (NullPointerException expected) { /* OK */ }
        RetryGuard g = new RetryGuard(2, 100, RetryGuard.defaultRetryOn());
        Assert.assertEquals(2, g.getMaxRetries());
        Assert.assertEquals(100L, g.getBaseDelayMs());
        Assert.assertTrue(g.getRetryOn().size() >= 2);
    }

    @Test(timeout = 30000)
    public void tr1_2_CircuitBreakerGuard_CtorIAE() {
        try { new CircuitBreakerGuard(-0.1, 50, 5000); Assert.fail("expected IAE"); }
        catch (IllegalArgumentException expected) { /* OK */ }
        try { new CircuitBreakerGuard(0.5, 0, 5000); Assert.fail("expected IAE"); }
        catch (IllegalArgumentException expected) { /* OK */ }
        try { new CircuitBreakerGuard(0.5, 50, 0); Assert.fail("expected IAE"); }
        catch (IllegalArgumentException expected) { /* OK */ }
        CircuitBreakerGuard g = new CircuitBreakerGuard(0.5, 50, 5000);
        Assert.assertEquals(CircuitBreakerGuard.State.CLOSED, g.getState());
        Assert.assertEquals(0.5, g.getFailureRateThreshold(), 1e-9);
    }

    @Test(timeout = 30000)
    public void tr1_2_RateLimiterGuard_CtorIAE() {
        try { new RateLimiterGuard(0, 100); Assert.fail("expected IAE"); }
        catch (IllegalArgumentException expected) { /* OK */ }
        try { new RateLimiterGuard(100, 0); Assert.fail("expected IAE"); }
        catch (IllegalArgumentException expected) { /* OK */ }
        RateLimiterGuard g = new RateLimiterGuard(100, 200);
        Assert.assertEquals(100, g.getQps());
        Assert.assertEquals(200, g.getBurst());
        Assert.assertTrue(g.getAvailableTokens() <= 200);
    }

    // ============== TR1.3 4 异常类型 ==============

    @Test(timeout = 30000)
    public void tr1_3_FourExceptions() {
        RateLimitExceededException rl = new RateLimitExceededException("rl", 250L, 100);
        Assert.assertEquals(250L, rl.getConsumedTokens());
        Assert.assertEquals(100, rl.getQpsLimit());
        Assert.assertTrue(rl instanceof RuntimeException);

        CircuitOpenException co = new CircuitOpenException("co", 12345L, 5000L, 0.7);
        Assert.assertEquals(12345L, co.getOpenedAtNanos());
        Assert.assertEquals(5000L, co.getOpenDurationMs());
        Assert.assertEquals(0.7, co.getFailureRate(), 1e-9);
        Assert.assertTrue(co instanceof RuntimeException);

        RetryExhaustedException re = new RetryExhaustedException("re", 3, new RuntimeException("root"));
        Assert.assertEquals(3, re.getAttempts());
        Assert.assertNotNull(re.getCause());
        Assert.assertTrue(re instanceof RuntimeException);

        QueryTimeoutException qt = new QueryTimeoutException("qt", 1000L, 1500L);
        Assert.assertEquals(1000L, qt.getTimeoutMs());
        Assert.assertEquals(1500L, qt.getElapsedMs());
        Assert.assertTrue(qt instanceof RuntimeException);
    }

    // ============== TR1.4 状态可观测 ==============

    @Test(timeout = 30000)
    public void tr1_4_StateObservability() throws Exception {
        // TimeoutGuard activeCallCount
        TimeoutGuard tg = new TimeoutGuard(2000);
        final boolean[] done = {false};
        Callable<Integer> fast = () -> { Thread.sleep(50); done[0] = true; return 1; };
        Integer r = tg.wrap(fast);
        Assert.assertEquals(Integer.valueOf(1), r);
        Assert.assertTrue(done[0]);
        // 调用结束后 activeCount 应回到 0（可能存在微小延迟，最多重试 5 次）
        long active = -1;
        for (int i = 0; i < 5; i++) {
            active = tg.getActiveCallCount();
            if (active == 0) break;
            Thread.sleep(10);
        }
        Assert.assertEquals(0L, active);
        tg.shutdown();

        // CircuitBreakerGuard 状态从 CLOSED 起步
        CircuitBreakerGuard cb = new CircuitBreakerGuard(0.5, 10, 1000);
        Assert.assertEquals(CircuitBreakerGuard.State.CLOSED, cb.getState());
        Assert.assertEquals(0.0, cb.currentFailureRate(), 1e-9);

        // RateLimiterGuard 初始令牌数 ≤ burst
        RateLimiterGuard rl = new RateLimiterGuard(100, 200);
        long initial = rl.getAvailableTokens();
        Assert.assertTrue("initial tokens should be <= burst, got " + initial, initial >= 0 && initial <= 200);
    }
}
