package ds;

import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.cluster.resilience.CircuitBreakerGuard;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.AdaptiveThresholdController;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedCircuitBreakerState;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedResilienceConfig;

/**
 * P36 T1：DistributedCircuitBreakerState + AdaptiveThresholdController skeleton，5 TR 0F0E。
 *
 * <p>
 * TR1.1 DistributedCircuitBreakerState 不可变 POJO 5 IAE
 * <br>TR1.2 fingerprint SHA-256 64-hex 跨 run 一致
 * <br>TR1.3 closedDefault 工厂方法
 * <br>TR1.4 AdaptiveThresholdController.adjustThreshold AimdLike 收敛
 * <br>TR1.5 AdaptiveThresholdController.adjustOpenDuration AimdLike 收敛
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P36_T1_DistributedStateAndAdaptiveTest {

    /* -------- TR1.1 DistributedCircuitBreakerState 5 IAE -------- */
    @Test
    public void tr11_state5Iae() {
        // 正常构造
        DistributedCircuitBreakerState s = new DistributedCircuitBreakerState(
                1, CircuitBreakerGuard.State.CLOSED, 0L, 0.0, 0.5, 5_000L, 1000L);
        Assert.assertEquals(1, s.serverId);
        Assert.assertEquals(CircuitBreakerGuard.State.CLOSED, s.state);
        // 5 IAE
        try {
            new DistributedCircuitBreakerState(
                    -1, CircuitBreakerGuard.State.CLOSED, 0L, 0.0, 0.5, 5_000L, 1000L);
            Assert.fail("serverId<0 IAE");
        } catch (IllegalArgumentException ignore) {}
        try {
            new DistributedCircuitBreakerState(
                    1, null, 0L, 0.0, 0.5, 5_000L, 1000L);
            Assert.fail("state null IAE");
        } catch (IllegalArgumentException ignore) {}
        try {
            new DistributedCircuitBreakerState(
                    1, CircuitBreakerGuard.State.CLOSED, 0L, 1.5, 0.5, 5_000L, 1000L);
            Assert.fail("failureRate>1 IAE");
        } catch (IllegalArgumentException ignore) {}
        try {
            new DistributedCircuitBreakerState(
                    1, CircuitBreakerGuard.State.CLOSED, 0L, 0.0, -0.1, 5_000L, 1000L);
            Assert.fail("currentThreshold<0 IAE");
        } catch (IllegalArgumentException ignore) {}
        try {
            new DistributedCircuitBreakerState(
                    1, CircuitBreakerGuard.State.CLOSED, 0L, 0.0, 0.5, 0L, 1000L);
            Assert.fail("currentOpenDurationMs<1 IAE");
        } catch (IllegalArgumentException ignore) {}
    }

    /* -------- TR1.2 fingerprint SHA-256 64-hex 跨 run 一致 -------- */
    @Test
    public void tr12_fingerprint() {
        DistributedCircuitBreakerState s1 = new DistributedCircuitBreakerState(
                1, CircuitBreakerGuard.State.OPEN, 12345L, 0.75, 0.3, 5_000L, 1000L);
        DistributedCircuitBreakerState s2 = new DistributedCircuitBreakerState(
                1, CircuitBreakerGuard.State.OPEN, 12345L, 0.75, 0.3, 5_000L, 1000L);
        String fp1 = DistributedCircuitBreakerState.fingerprint(s1);
        String fp2 = DistributedCircuitBreakerState.fingerprint(s2);
        Assert.assertEquals(64, fp1.length());
        Assert.assertTrue("fingerprint hex", fp1.matches("[0-9a-f]{64}"));
        Assert.assertEquals("fingerprint 跨 run 一致", fp1, fp2);
        // 字段差异 → fingerprint 差异
        DistributedCircuitBreakerState s3 = new DistributedCircuitBreakerState(
                2, CircuitBreakerGuard.State.OPEN, 12345L, 0.75, 0.3, 5_000L, 1000L);
        Assert.assertNotEquals(fp1, DistributedCircuitBreakerState.fingerprint(s3));
    }

    /* -------- TR1.3 closedDefault 工厂方法 -------- */
    @Test
    public void tr13_closedDefault() {
        DistributedCircuitBreakerState s = DistributedCircuitBreakerState.closedDefault(42);
        Assert.assertEquals(42, s.serverId);
        Assert.assertEquals(CircuitBreakerGuard.State.CLOSED, s.state);
        Assert.assertEquals(0.0, s.failureRate, 0.0);
        Assert.assertEquals(0.5, s.currentThreshold, 0.0);
        Assert.assertEquals(5_000L, s.currentOpenDurationMs);
        Assert.assertTrue("sampleTimestampMs > 0", s.sampleTimestampMs > 0L);
    }

    /* -------- TR1.4 Adaptive adjustThreshold AimdLike 收敛 -------- */
    @Test
    public void tr14_adaptiveThreshold() {
        DistributedResilienceConfig cfg = DistributedResilienceConfig.defaults();
        // 高失败率 → 阈值下降
        double[] highFr = {0.8, 0.9, 0.85};
        double newT = AdaptiveThresholdController.adjustThreshold(0.5, highFr, cfg);
        Assert.assertTrue("high FR → threshold 下降 (< 0.5): " + newT, newT < 0.5);
        // 低失败率 → 阈值上升
        double[] lowFr = {0.05, 0.1, 0.15};
        double newT2 = AdaptiveThresholdController.adjustThreshold(0.5, lowFr, cfg);
        Assert.assertTrue("low FR → threshold 上升 (> 0.5): " + newT2, newT2 > 0.5);
        // 稳态区 → 阈值保持
        double[] midFr = {0.4, 0.5, 0.6};
        double newT3 = AdaptiveThresholdController.adjustThreshold(0.5, midFr, cfg);
        Assert.assertEquals("稳态区 threshold 保持", 0.5, newT3, 1e-9);
        // 4 IAE
        try {
            AdaptiveThresholdController.adjustThreshold(-0.1, highFr, cfg);
            Assert.fail("currentThreshold<0 IAE");
        } catch (IllegalArgumentException ignore) {}
        try {
            AdaptiveThresholdController.adjustThreshold(0.5, new double[0], cfg);
            Assert.fail("empty rates IAE");
        } catch (IllegalArgumentException ignore) {}
        try {
            AdaptiveThresholdController.adjustThreshold(0.5, highFr, null);
            Assert.fail("null config IAE");
        } catch (java.lang.NullPointerException ignore) {}
        try {
            AdaptiveThresholdController.adjustThreshold(0.5, new double[]{1.5}, cfg);
            Assert.fail("sample>1 IAE");
        } catch (IllegalArgumentException ignore) {}
    }

    /* -------- TR1.5 Adaptive adjustOpenDuration AimdLike 收敛 -------- */
    @Test
    public void tr15_adaptiveOpenDuration() {
        DistributedResilienceConfig cfg = DistributedResilienceConfig.defaults();
        double[] highFr = {0.8, 0.9, 0.85};
        long newD = AdaptiveThresholdController.adjustOpenDuration(5_000L, highFr, cfg);
        Assert.assertTrue("high FR → openDuration 下降 (< 5s): " + newD, newD < 5_000L);
        double[] lowFr = {0.05, 0.1, 0.15};
        long newD2 = AdaptiveThresholdController.adjustOpenDuration(5_000L, lowFr, cfg);
        Assert.assertTrue("low FR → openDuration 上升 (> 5s): " + newD2, newD2 > 5_000L);
        double[] midFr = {0.4, 0.5, 0.6};
        long newD3 = AdaptiveThresholdController.adjustOpenDuration(5_000L, midFr, cfg);
        Assert.assertEquals("稳态 openDuration 保持", 5_000L, newD3);
        // 下限 ≥ 1000ms
        long newD4 = AdaptiveThresholdController.adjustOpenDuration(1_500L, highFr, cfg);
        Assert.assertTrue("下限 ≥ 1000ms: " + newD4, newD4 >= 1_000L);
    }
}