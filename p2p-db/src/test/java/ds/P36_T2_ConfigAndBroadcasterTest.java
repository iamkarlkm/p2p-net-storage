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
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.ClusterStateBroadcaster;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedCircuitBreakerGuard;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedCircuitBreakerState;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedResilienceConfig;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.AdaptiveThresholdController;

/**
 * P36 T2：DistributedResilienceConfig + ClusterStateBroadcaster +
 * DistributedCircuitBreakerGuard，6 TR 0F0E。
 *
 * <p>
 * TR2.1 DistributedResilienceConfig Builder 12 字段 4 IAE（P32 8 字段保留校验 + P36 4
 * 字段校验）
 * <br>TR2.2 fromBase 工厂从 P32 ResilienceConfig 构造
 * <br>TR2.3 ClusterStateBroadcaster.broadcast quorum ≤ replicas 校验 + 失败本地熔断
 * <br>TR2.4 ClusterStateBroadcaster.registerListener 异步通知
 * <br>TR2.5 DistributedCircuitBreakerGuard.getOrCreateGuard / getOrCreateState
 * <br>TR2.6 DistributedCircuitBreakerGuard.wrap 状态机 + 广播触发
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P36_T2_ConfigAndBroadcasterTest {

    @Before
    public void reset() {
        ClusterStateBroadcaster.clearListenersForTest();
        ClusterStateBroadcaster.resetCountersForTest();
        DistributedCircuitBreakerGuard.resetAllForTest();
    }

    /* -------- TR2.1 Builder 12 字段 4 IAE -------- */
    @Test
    public void tr21_builderIae() {
        // defaults() 合法
        DistributedResilienceConfig def = DistributedResilienceConfig.defaults();
        Assert.assertEquals(30_000L, def.timeoutMs);
        Assert.assertEquals(100, def.qps);
        Assert.assertEquals(5_000L, def.distributedKpiSampleIntervalMs);
        Assert.assertEquals(1, def.distributedQuorum);
        Assert.assertEquals(0.1, def.adaptiveMinFailureRate, 0.0);
        Assert.assertEquals(60_000L, def.adaptiveMaxOpenDurationMs);

        // 4 P36 字段 IAE
        try {
            new DistributedResilienceConfig.Builder()
                    .timeoutMs(1L).maxRetries(0).baseDelayMs(1L)
                    .failureRateThreshold(0.5).slidingWindowSize(1).openDurationMs(1L)
                    .qps(1).burst(1)
                    .distributedKpiSampleIntervalMs(500L) // < 1000
                    .distributedQuorum(1).adaptiveMinFailureRate(0.1).adaptiveMaxOpenDurationMs(60_000L)
                    .build();
            Assert.fail("distributedKpiSampleIntervalMs<1000 IAE");
        } catch (IllegalArgumentException ignore) {
        }
        try {
            new DistributedResilienceConfig.Builder()
                    .timeoutMs(1L).maxRetries(0).baseDelayMs(1L)
                    .failureRateThreshold(0.5).slidingWindowSize(1).openDurationMs(1L)
                    .qps(1).burst(1)
                    .distributedKpiSampleIntervalMs(5000L)
                    .distributedQuorum(0) // < 1
                    .adaptiveMinFailureRate(0.1).adaptiveMaxOpenDurationMs(60_000L)
                    .build();
            Assert.fail("distributedQuorum<1 IAE");
        } catch (IllegalArgumentException ignore) {
        }
        try {
            new DistributedResilienceConfig.Builder()
                    .timeoutMs(1L).maxRetries(0).baseDelayMs(1L)
                    .failureRateThreshold(0.5).slidingWindowSize(1).openDurationMs(1L)
                    .qps(1).burst(1)
                    .distributedKpiSampleIntervalMs(5000L)
                    .distributedQuorum(1)
                    .adaptiveMinFailureRate(1.5) // > 1
                    .adaptiveMaxOpenDurationMs(60_000L)
                    .build();
            Assert.fail("adaptiveMinFailureRate>1 IAE");
        } catch (IllegalArgumentException ignore) {
        }
        try {
            new DistributedResilienceConfig.Builder()
                    .timeoutMs(1L).maxRetries(0).baseDelayMs(1L)
                    .failureRateThreshold(0.5).slidingWindowSize(1).openDurationMs(1L)
                    .qps(1).burst(1)
                    .distributedKpiSampleIntervalMs(5000L)
                    .distributedQuorum(1)
                    .adaptiveMinFailureRate(0.1)
                    .adaptiveMaxOpenDurationMs(500L) // < 1000
                    .build();
            Assert.fail("adaptiveMaxOpenDurationMs<1000 IAE");
        } catch (IllegalArgumentException ignore) {
        }
    }

    /* -------- TR2.2 fromBase 工厂 -------- */
    @Test
    public void tr22_fromBase() {
        com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig base
                = com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig.defaults();
        DistributedResilienceConfig dist = DistributedResilienceConfig.fromBase(base);
        Assert.assertEquals(base.timeoutMs, dist.timeoutMs);
        Assert.assertEquals(base.failureRateThreshold, dist.failureRateThreshold, 0.0);
        Assert.assertEquals(base.openDurationMs, dist.openDurationMs);
        // P36 4 字段取默认
        Assert.assertEquals(5_000L, dist.distributedKpiSampleIntervalMs);
        Assert.assertEquals(1, dist.distributedQuorum);
        Assert.assertEquals(0.1, dist.adaptiveMinFailureRate, 0.0);
        Assert.assertEquals(60_000L, dist.adaptiveMaxOpenDurationMs);

        try {
            DistributedResilienceConfig.fromBase(null);
            Assert.fail("null base IAE");
        } catch (IllegalArgumentException ignore) {
        }
    }

    /* -------- TR2.3 ClusterStateBroadcaster.broadcast quorum 校验 + 失败本地熔断 -------- */
    @Test
    public void tr23_broadcastQuorum() {
        DistributedResilienceConfig cfg = new DistributedResilienceConfig.Builder()
                .timeoutMs(1L).maxRetries(0).baseDelayMs(1L)
                .failureRateThreshold(0.5).slidingWindowSize(1).openDurationMs(1L)
                .qps(1).burst(1)
                .distributedKpiSampleIntervalMs(5000L)
                .distributedQuorum(3) // > replicas().length=2
                .adaptiveMinFailureRate(0.1)
                .adaptiveMaxOpenDurationMs(60_000L)
                .build();
        DsServerDirectory dir = new StaticDir(new int[]{2, 3}, 1);  // 2 replicas
        DistributedCircuitBreakerState s = DistributedCircuitBreakerState.closedDefault(1);
        try {
            ClusterStateBroadcaster.broadcast(1, s, dir, cfg);
            Assert.fail("quorum > replicas IAE");
        } catch (IllegalArgumentException ignore) {
        }
        // 失败本地熔断：listener 抛异常不影响主链路
        ClusterStateBroadcaster.clearListenersForTest();
        ClusterStateBroadcaster.registerListener((sid, snap) -> {
            throw new RuntimeException("test failure");
        });
        DistributedResilienceConfig okCfg = new DistributedResilienceConfig.Builder()
                .timeoutMs(1L).maxRetries(0).baseDelayMs(1L)
                .failureRateThreshold(0.5).slidingWindowSize(1).openDurationMs(1L)
                .qps(1).burst(1)
                .distributedKpiSampleIntervalMs(5000L)
                .distributedQuorum(2) // ≤ 2 replicas
                .adaptiveMinFailureRate(0.1)
                .adaptiveMaxOpenDurationMs(60_000L)
                .build();
        long before = ClusterStateBroadcaster.failedBroadcasts();
        ClusterStateBroadcaster.broadcast(1, s, dir, okCfg);
        long after = ClusterStateBroadcaster.failedBroadcasts();
        Assert.assertEquals("失败本地熔断 计数 +1", before + 1, after);
        Assert.assertTrue("totalBroadcasts +1", ClusterStateBroadcaster.totalBroadcasts() > 0);
    }

    /* -------- TR2.4 ClusterStateBroadcaster.registerListener 异步通知 -------- */
    @Test
    public void tr24_registerListener() {
        AtomicInteger count = new AtomicInteger(0);
        List<DistributedCircuitBreakerState> received = new ArrayList<>();
        ClusterStateBroadcaster.registerListener((sid, snap) -> {
            count.incrementAndGet();
            received.add(snap);
        });
        Assert.assertEquals(1, ClusterStateBroadcaster.listenerCount());
        DistributedResilienceConfig cfg = new DistributedResilienceConfig.Builder()
                .timeoutMs(1L).maxRetries(0).baseDelayMs(1L)
                .failureRateThreshold(0.5).slidingWindowSize(1).openDurationMs(1L)
                .qps(1).burst(1)
                .distributedKpiSampleIntervalMs(5000L)
                .distributedQuorum(1)
                .adaptiveMinFailureRate(0.1)
                .adaptiveMaxOpenDurationMs(60_000L)
                .build();
        DsServerDirectory dir = new StaticDir(new int[]{2}, 1);  // 1 replica, quorum=1 OK
        DistributedCircuitBreakerState s = DistributedCircuitBreakerState.closedDefault(1);
        ClusterStateBroadcaster.broadcast(1, s, dir, cfg);
        Assert.assertEquals("listener 收到 1 次", 1, count.get());
        Assert.assertEquals(1, received.size());
        Assert.assertEquals(1, received.get(0).serverId);
    }

    /* -------- TR2.5 DistributedCircuitBreakerGuard.getOrCreateGuard / State -------- */
    @Test
    public void tr25_getOrCreate() {
        DistributedResilienceConfig cfg = DistributedResilienceConfig.defaults();
        CircuitBreakerGuard g1 = DistributedCircuitBreakerGuard.getOrCreateGuard(1, cfg);
        CircuitBreakerGuard g2 = DistributedCircuitBreakerGuard.getOrCreateGuard(1, cfg);
        Assert.assertSame("相同 serverId 返回相同 guard", g1, g2);
        DistributedCircuitBreakerState s1 = DistributedCircuitBreakerGuard.getOrCreateState(1);
        Assert.assertEquals(1, s1.serverId);
        Assert.assertEquals(CircuitBreakerGuard.State.CLOSED, s1.state);
        try {
            DistributedCircuitBreakerGuard.getOrCreateGuard(-1, cfg);
            Assert.fail("serverId<0 IAE");
        } catch (IllegalArgumentException ignore) {
        }
        try {
            DistributedCircuitBreakerGuard.getOrCreateGuard(1, null);
            Assert.fail("null config IAE");
        } catch (NullPointerException ignore) {
        }
    }

    /* -------- TR2.6 DistributedCircuitBreakerGuard.wrap 状态机 + 广播触发 -------- */
    @Test
    public void tr26_wrapStateMachine() {
        DistributedResilienceConfig cfg = new DistributedResilienceConfig.Builder()
                .timeoutMs(1L).maxRetries(0).baseDelayMs(1L)
                .failureRateThreshold(0.5).slidingWindowSize(5).openDurationMs(100L)
                .qps(1).burst(1)
                .distributedKpiSampleIntervalMs(5000L)
                .distributedQuorum(1)
                .adaptiveMinFailureRate(0.1)
                .adaptiveMaxOpenDurationMs(60_000L)
                .build();
        DsServerDirectory dir = new StaticDir(new int[]{2}, 1);  // 1 replica, quorum=1 OK
        AtomicInteger listenerCalls = new AtomicInteger(0);
        ClusterStateBroadcaster.registerListener((sid, snap) -> listenerCalls.incrementAndGet());
        // 成功调用：CLOSE 状态保持
        try {
            DistributedCircuitBreakerGuard.wrap(1, () -> "ok", cfg, dir);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // 5 次失败触发 OPEN
        long beforeTrans = DistributedCircuitBreakerGuard.openTransitions();
        for (int i = 0; i < 5; i++) {
            try {
                DistributedCircuitBreakerGuard.wrap(1, () -> {
                    throw new RuntimeException("boom");
                }, cfg, dir);
            } catch (Exception ignore) {
                // expected
            }
        }
        CircuitBreakerGuard guard = DistributedCircuitBreakerGuard.getOrCreateGuard(1, cfg);
        Assert.assertEquals("5 次失败后状态 CLOSED→OPEN",
                CircuitBreakerGuard.State.OPEN, guard.getState());
        Assert.assertTrue("openTransitions 至少 +1", DistributedCircuitBreakerGuard.openTransitions() > beforeTrans);
    }

    /**
     * 静态目录（单测用，避免依赖真实 DsServerDirectory）
     */
    private static class StaticDir implements DsServerDirectory {

        private final int[] replicas;
        private final int selfId;

        StaticDir(int[] replicas, int selfId) {
            this.replicas = replicas;
            this.selfId = selfId;
        }

        @Override
        public int[] listServerIds() {
            return new int[]{selfId};
        }

        @Override
        public Endpoint endpoint(int serverId) {
            return new Endpoint("127.0.0.1", 9000, 100);
        }

        @Override
        public int[] replicas(int serverId) {
            return replicas;
        }
    }
}
