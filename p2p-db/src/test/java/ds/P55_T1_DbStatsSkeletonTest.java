package ds;

import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.cluster.stats.DbStats;
import com.q3lives.ds.cluster.stats.DbStatsRegistry;

/**
 * P55 T1：快照模型 + 注册表骨架 + IAE（5 TR）。
 *
 * <p>
 * tr11 {@code DbStats.toMap} 五组键齐全；tr12 三类注册 IAE 守卫；tr13 {@code unregister} 移除；
 * tr14 {@code clear()} 归零；tr15 空注册 → 全 0 快照。
 */
public class P55_T1_DbStatsSkeletonTest {

    @Before
    public void setUp() {
        DbStatsRegistry.clear();
    }

    @After
    public void tearDown() {
        DbStatsRegistry.clear();
    }

    @Test(timeout = 30000)
    public void tr11_toMapHasFiveGroupsAndFields() {
        DbStats s = new DbStats(0.5d, 3, 4096L, 7L, 9L, 111L, 2.5d, 4L, 6L);
        Map<String, Object> m = s.toMap();
        Assert.assertEquals("必须恰 5 组键", 5, m.size());
        Assert.assertTrue(m.containsKey("cache"));
        Assert.assertTrue(m.containsKey("wal"));
        Assert.assertTrue(m.containsKey("txn"));
        Assert.assertTrue(m.containsKey("compaction"));
        Assert.assertTrue(m.containsKey("replica"));

        Map<?, ?> cache = (Map<?, ?>) m.get("cache");
        Assert.assertEquals(0.5d, ((Number) cache.get("hitRate")).doubleValue(), 1e-9);
        Assert.assertEquals(3, ((Number) cache.get("activeBlocks")).intValue());
        Assert.assertEquals(4096L, ((Number) cache.get("cachedBytes")).longValue());

        Map<?, ?> wal = (Map<?, ?>) m.get("wal");
        Assert.assertEquals(7L, ((Number) wal.get("replayLag")).longValue());

        Map<?, ?> txn = (Map<?, ?>) m.get("txn");
        Assert.assertEquals(9L, ((Number) txn.get("count")).longValue());
        Assert.assertEquals(111L, ((Number) txn.get("latencyP99Nanos")).longValue());

        Map<?, ?> comp = (Map<?, ?>) m.get("compaction");
        Assert.assertEquals(2.5d, ((Number) comp.get("bytesPerSec")).doubleValue(), 1e-9);
        Assert.assertEquals(4L, ((Number) comp.get("runsSucceeded")).longValue());

        Map<?, ?> replica = (Map<?, ?>) m.get("replica");
        Assert.assertEquals(6L, ((Number) replica.get("lag")).longValue());
    }

    @Test(timeout = 30000)
    public void tr12_registerGuardsThrowIae() {
        assertIae("registerCache(null name)", () -> DbStatsRegistry.registerCache(null, () -> null));
        assertIae("registerCompaction(empty name)",
                () -> DbStatsRegistry.registerCompaction("", () -> null));
        assertIae("registerReplicator(null src)",
                () -> DbStatsRegistry.registerReplicator("r", null));
        assertIae("registerCache(null src)",
                () -> DbStatsRegistry.registerCache("c", null));
        assertIae("registerCompaction(tickMs<=0)",
                () -> DbStatsRegistry.registerCompaction("c", 0L, () -> null));
    }

    @Test(timeout = 30000)
    public void tr13_unregisterRemovesSource() {
        DbStatsRegistry.registerReplicator("r1", () -> 5L);
        Assert.assertEquals(5L, DbStatsRegistry.snapshot().replicaLag());
        DbStatsRegistry.unregister("r1");
        Assert.assertEquals("unregister 后源必须移除", 0L, DbStatsRegistry.snapshot().replicaLag());
        DbStatsRegistry.unregister(null);
        Assert.assertNull("unregister(null) 必须为空操作", null);
    }

    @Test(timeout = 30000)
    public void tr14_clearResetsRegistryAndSampler() {
        DbStatsRegistry.registerReplicator("r1", () -> 5L);
        DbStatsRegistry.registerCache("c1", () -> null);
        DbStatsRegistry.recordTxnLatency(123L);
        Assert.assertTrue("采样计数应 > 0", DbStatsRegistry.txnCount() > 0L);

        DbStatsRegistry.clear();
        Assert.assertEquals(0L, DbStatsRegistry.txnCount());
        Assert.assertEquals(0L, DbStatsRegistry.txnLatencyP99Nanos());
        Assert.assertEquals(0L, DbStatsRegistry.snapshot().replicaLag());
        Assert.assertEquals(0L, DbStatsRegistry.snapshot().walReplayLag());
    }

    @Test(timeout = 30000)
    public void tr15_emptyRegistryYieldsAllZeroSnapshot() {
        DbStats s = DbStatsRegistry.snapshot();
        Assert.assertEquals(0.0d, s.cacheHitRate(), 0.0d);
        Assert.assertEquals(0, s.cacheActiveBlocks());
        Assert.assertEquals(0L, s.cacheCachedBytes());
        Assert.assertEquals(0L, s.walReplayLag());
        Assert.assertEquals(0L, s.txnCount());
        Assert.assertEquals(0L, s.txnLatencyP99Nanos());
        Assert.assertEquals(0.0d, s.compactionBytesPerSec(), 0.0d);
        Assert.assertEquals(0L, s.compactionRunsSucceeded());
        Assert.assertEquals(0L, s.replicaLag());
    }

    private interface Thrower {

        void run();
    }

    private static void assertIae(String label, Thrower t) {
        boolean thrown = false;
        try {
            t.run();
        } catch (IllegalArgumentException ex) {
            thrown = true;
        }
        Assert.assertTrue(label + " 必须抛 IllegalArgumentException", thrown);
    }
}
