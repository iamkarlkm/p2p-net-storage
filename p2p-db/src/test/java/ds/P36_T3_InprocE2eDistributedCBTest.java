package ds;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.database.fulltext.cluster.resilience.CircuitBreakerGuard;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.AdaptiveThresholdController;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.ClusterStateBroadcaster;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedCircuitBreakerGuard;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedCircuitBreakerState;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedResilienceConfig;

/**
 * P36 T3：Inproc E2E 分布式 CB + Adaptive 调参，3 TR 0F0E。
 *
 * <p>
 * TR3.1 多 serverId 独立 CB 状态机：serverId=1 OPEN 不影响 serverId=2
 * <br>TR3.2 Adaptive 阈值调整触发 broadcast 后下游 listener 收到新阈值
 * <br>TR3.3 AimdLike 收敛路径：连续 HIGH 触发 DECREASE + 连续 LOW 触发 INCREASE
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P36_T3_InprocE2eDistributedCBTest {

    @Before
    public void reset() {
        ClusterStateBroadcaster.clearListenersForTest();
        ClusterStateBroadcaster.resetCountersForTest();
        DistributedCircuitBreakerGuard.resetAllForTest();
    }

    /* -------- TR3.1 多 serverId 独立 CB 状态机 -------- */
    @Test
    public void tr31_multiServerIsolation() throws Exception {
        DistributedResilienceConfig cfg = new DistributedResilienceConfig.Builder()
                .timeoutMs(1L).maxRetries(0).baseDelayMs(1L)
                .failureRateThreshold(0.5).slidingWindowSize(5).openDurationMs(60_000L)
                .qps(1).burst(1)
                .distributedKpiSampleIntervalMs(5000L)
                .distributedQuorum(1)
                .adaptiveMinFailureRate(0.1)
                .adaptiveMaxOpenDurationMs(60_000L)
                .build();
        DsServerDirectory dir = new StaticDir3(new int[]{2, 3});
        // serverId=1 推 5 次失败 → OPEN
        for (int i = 0; i < 5; i++) {
            try {
                DistributedCircuitBreakerGuard.wrap(1, () -> {
                    throw new RuntimeException("boom-1");
                }, cfg, dir);
            } catch (Exception ignore) {
            }
        }
        // serverId=2 全部成功 → CLOSED
        for (int i = 0; i < 5; i++) {
            DistributedCircuitBreakerGuard.wrap(2, () -> "ok", cfg, dir);
        }
        CircuitBreakerGuard g1 = DistributedCircuitBreakerGuard.getOrCreateGuard(1, cfg);
        CircuitBreakerGuard g2 = DistributedCircuitBreakerGuard.getOrCreateGuard(2, cfg);
        Assert.assertEquals(CircuitBreakerGuard.State.OPEN, g1.getState());
        Assert.assertEquals(CircuitBreakerGuard.State.CLOSED, g2.getState());
        // 状态快照各自独立
        DistributedCircuitBreakerState s1 = DistributedCircuitBreakerGuard.getOrCreateState(1);
        DistributedCircuitBreakerState s2 = DistributedCircuitBreakerGuard.getOrCreateState(2);
        Assert.assertEquals(CircuitBreakerGuard.State.OPEN, s1.state);
        Assert.assertEquals(CircuitBreakerGuard.State.CLOSED, s2.state);
        Assert.assertNotEquals(s1.failureRate, s2.failureRate, 0.0);
    }

    /* -------- TR3.2 Adaptive 阈值调整触发 broadcast 下游 listener 收到新阈值 -------- */
    @Test
    public void tr32_adaptiveThresholdBroadcast() {
        DistributedResilienceConfig cfg = DistributedResilienceConfig.defaults();
        List<Double> observedThresholds = new ArrayList<>();
        ClusterStateBroadcaster.registerListener((sid, snap) -> {
            observedThresholds.add(snap.currentThreshold);
        });
        // 模拟 3 个高失败率样本 → DECREASE 路径
        double initial = cfg.failureRateThreshold;
        double adjusted = initial;
        for (int i = 0; i < 3; i++) {
            // 模拟一个高失败率样本：0.9
            double[] rates = {0.9};
            adjusted = AdaptiveThresholdController.adjustThreshold(adjusted, rates, cfg);
        }
        Assert.assertTrue(
                "连续 HIGH 失败率后阈值应 < initial, actual adjusted=" + adjusted,
                adjusted < initial);
        // broadcast 一次，断言 listener 收到 adjusted
        DistributedCircuitBreakerState snap = new DistributedCircuitBreakerState(
                1, CircuitBreakerGuard.State.CLOSED,
                0L, 0.9, adjusted, cfg.openDurationMs, System.currentTimeMillis());
        DsServerDirectory dir = new StaticDir3(new int[]{2});
        long before = ClusterStateBroadcaster.totalBroadcasts();
        ClusterStateBroadcaster.broadcast(1, snap, dir, cfg);
        Assert.assertEquals("broadcast +1", before + 1, ClusterStateBroadcaster.totalBroadcasts());
        Assert.assertTrue("listener 收到新阈值", observedThresholds.contains(adjusted));
    }

    /* -------- TR3.3 AimdLike 收敛路径 -------- */
    @Test
    public void tr33_aimdConvergence() {
        DistributedResilienceConfig cfg = DistributedResilienceConfig.defaults();
        double startThreshold = cfg.failureRateThreshold;
        // 连续 5 个 LOW（0.1）→ INCREASE 路径
        double increased = startThreshold;
        for (int i = 0; i < 5; i++) {
            double[] rates = {0.1};
            increased = AdaptiveThresholdController.adjustThreshold(increased, rates, cfg);
        }
        Assert.assertTrue(
                "连续 LOW 后阈值应 > start, actual increased=" + increased,
                increased > startThreshold);
        // 连续 5 个 HIGH（0.9）→ DECREASE 路径
        double decreased = startThreshold;
        for (int i = 0; i < 5; i++) {
            double[] rates = {0.9};
            decreased = AdaptiveThresholdController.adjustThreshold(decreased, rates, cfg);
        }
        Assert.assertTrue(
                "连续 HIGH 后阈值应 < start, actual decreased=" + decreased,
                decreased < startThreshold);
        // 稳态（rate=0.5）保持
        double stable = startThreshold;
        for (int i = 0; i < 5; i++) {
            double[] rates = {0.5};
            stable = AdaptiveThresholdController.adjustThreshold(stable, rates, cfg);
        }
        Assert.assertEquals(
                "稳态 0.5 保持不变",
                startThreshold, stable, 0.001);
        // openDuration 同样路径
        long startOpen = cfg.openDurationMs;
        long increasedOpen = AdaptiveThresholdController.adjustOpenDuration(startOpen, new double[]{0.1}, cfg);
        Assert.assertTrue(
                "openDuration LOW → INCREASE, actual increasedOpen=" + increasedOpen,
                increasedOpen > startOpen);
        long decreasedOpen = AdaptiveThresholdController.adjustOpenDuration(startOpen, new double[]{0.9}, cfg);
        Assert.assertTrue(
                "openDuration HIGH → DECREASE, actual decreasedOpen=" + decreasedOpen,
                decreasedOpen < startOpen);
    }

    /**
     * 静态目录（多 serverId 模拟）
     */
    private static class StaticDir3 implements DsServerDirectory {

        private final int[] replicas;

        StaticDir3(int[] replicas) {
            this.replicas = replicas;
        }

        @Override
        public int[] listServerIds() {
            return new int[]{1, 2, 3};
        }

        @Override
        public Endpoint endpoint(int serverId) {
            return new Endpoint("127.0.0.1", 9000 + serverId, 100);
        }

        @Override
        public int[] replicas(int serverId) {
            return replicas;
        }
    }
}
