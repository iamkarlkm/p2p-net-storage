package ds;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.cluster.stats.DbStats;
import com.q3lives.ds.cluster.stats.DbStatsRegistry;
import com.q3lives.ds.core.DsMemory;
import com.q3lives.ds.database.fulltext.sched.CompactionStats;

/**
 * P55 T2：指标聚合正确性（6 TR）。
 *
 * <p>tr21 命中率公式；tr22 缓存占用透传；tr23 compaction 吞吐折算；tr24 {@code wal.replayLag} 求和；
 * tr25 {@code replica.lag} 取最大；tr26 多源聚合语义。
 */
public class P55_T2_AggregationTest {

    @Before
    public void setUp() {
        DbStatsRegistry.clear();
    }

    @After
    public void tearDown() {
        DbStatsRegistry.clear();
    }

    @Test(timeout = 30000)
    public void tr21_cacheHitRateFormulaAndZeroDenominator() {
        DbStatsRegistry.registerCache("c", () -> cache(2, 100L, 8L, 2L));
        Assert.assertEquals(0.8d, DbStatsRegistry.snapshot().cacheHitRate(), 1e-9);

        DbStatsRegistry.clear();
        DbStatsRegistry.registerCache("c", () -> cache(0, 0L, 0L, 0L));
        Assert.assertEquals("分母为 0 时命中率必须为 0", 0.0d,
                DbStatsRegistry.snapshot().cacheHitRate(), 0.0d);
    }

    @Test(timeout = 30000)
    public void tr22_cacheOccupancyPassthrough() {
        DbStatsRegistry.registerCache("c", () -> cache(3, 4096L, 0L, 0L));
        DbStats s = DbStatsRegistry.snapshot();
        Assert.assertEquals(3, s.cacheActiveBlocks());
        Assert.assertEquals(4096L, s.cacheCachedBytes());
    }

    @Test(timeout = 30000)
    public void tr23_compactionBytesPerSecAndZeroTicks() {
        CompactionStats cs = new CompactionStats();
        cs.recordTick();
        cs.recordTick();
        cs.recordRun(2, 6000L);
        DbStatsRegistry.registerCompaction("cp", 1000L, () -> cs);
        Assert.assertEquals("6000B / (2 tick × 1s) = 3000 B/s", 3000.0d,
                DbStatsRegistry.snapshot().compactionBytesPerSec(), 1e-9);

        DbStatsRegistry.clear();
        CompactionStats empty = new CompactionStats();
        DbStatsRegistry.registerCompaction("cp2", () -> empty);
        Assert.assertEquals("ticks=0 → 0", 0.0d,
                DbStatsRegistry.snapshot().compactionBytesPerSec(), 0.0d);
    }

    @Test(timeout = 30000)
    public void tr24_walReplayLagIsSumOfReplicators() {
        DbStatsRegistry.registerReplicator("r1", () -> 4L);
        DbStatsRegistry.registerReplicator("r2", () -> 6L);
        Assert.assertEquals(10L, DbStatsRegistry.snapshot().walReplayLag());
    }

    @Test(timeout = 30000)
    public void tr25_replicaLagIsMaxOfReplicators() {
        DbStatsRegistry.registerReplicator("r1", () -> 4L);
        DbStatsRegistry.registerReplicator("r2", () -> 9L);
        Assert.assertEquals(9L, DbStatsRegistry.snapshot().replicaLag());
    }

    @Test(timeout = 30000)
    public void tr26_multiSourceAggregationSemantics() {
        DbStatsRegistry.registerCache("c1", () -> cache(1, 100L, 1L, 1L));
        DbStatsRegistry.registerCache("c2", () -> cache(2, 300L, 9L, 0L));

        CompactionStats a = new CompactionStats();
        a.recordTick();
        a.recordRun(1, 1000L);
        CompactionStats b = new CompactionStats();
        b.recordTick();
        b.recordRun(1, 1000L);
        DbStatsRegistry.registerCompaction("cpA", 1000L, () -> a);
        DbStatsRegistry.registerCompaction("cpB", 1000L, () -> b);

        DbStats s = DbStatsRegistry.snapshot();
        Assert.assertEquals("活跃块数累加", 3, s.cacheActiveBlocks());
        Assert.assertEquals("缓存字节累加", 400L, s.cacheCachedBytes());
        Assert.assertEquals("命中率按总量合并", 10.0d / 11.0d, s.cacheHitRate(), 1e-9);
        Assert.assertEquals("成功合并次数累加", 2L, s.compactionRunsSucceeded());
        Assert.assertEquals("吞吐按总秒数折算", 1000.0d, s.compactionBytesPerSec(), 1e-9);
    }

    private static DsMemory.CacheStats cache(int active, long bytes, long hits, long misses) {
        return new DsMemory.CacheStats(16, 1L << 20, active, bytes, 0, active,
                0L, 0L, 0L, 0L, 0L, 0L, hits, misses);
    }
}
