package ds;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.cluster.stats.DbStatsRegistry;

/**
 * P55 T3：事务延迟 p99 采样（5 TR）。
 *
 * <p>tr31 空样本 → 0；tr32 单样本 → 该值；tr33 1000 样本已知分布 → p99 精确；tr34 环形覆盖只保留最近 N；
 * tr35 {@code txnCount} 单调累加。
 */
public class P55_T3_TxnLatencyTest {

    private static final int CAPACITY = 1024;

    @Before
    public void setUp() {
        DbStatsRegistry.clear();
    }

    @After
    public void tearDown() {
        DbStatsRegistry.clear();
    }

    @Test(timeout = 30000)
    public void tr31_emptySamplesYieldZero() {
        Assert.assertEquals(0L, DbStatsRegistry.txnLatencyP99Nanos());
    }

    @Test(timeout = 30000)
    public void tr32_singleSampleYieldsThatValue() {
        DbStatsRegistry.recordTxnLatency(777L);
        Assert.assertEquals(777L, DbStatsRegistry.txnLatencyP99Nanos());
    }

    @Test(timeout = 30000)
    public void tr33_knownDistributionYieldsExactP99() {
        for (int i = 1; i <= 1000; i++) {
            DbStatsRegistry.recordTxnLatency(i);
        }
        // ceil(0.99 × 1000) − 1 = 989 → 升序第 990 个 = 990
        Assert.assertEquals(990L, DbStatsRegistry.txnLatencyP99Nanos());
    }

    @Test(timeout = 30000)
    public void tr34_ringBufferKeepsOnlyLatestN() {
        for (int i = 1; i <= CAPACITY; i++) {
            DbStatsRegistry.recordTxnLatency(i);
        }
        for (int i = CAPACITY + 1; i <= 2 * CAPACITY; i++) {
            DbStatsRegistry.recordTxnLatency(i);
        }
        // 窗口 = [1025, 2048]；ceil(0.99 × 1024) − 1 = 1013 → 1025 + 1013 = 2038
        Assert.assertEquals(2038L, DbStatsRegistry.txnLatencyP99Nanos());
        Assert.assertEquals(2L * CAPACITY, DbStatsRegistry.txnCount());
    }

    @Test(timeout = 30000)
    public void tr35_txnCountMonotonic() {
        Assert.assertEquals(0L, DbStatsRegistry.txnCount());
        for (int i = 0; i < 3; i++) {
            DbStatsRegistry.recordTxnLatency(10L);
        }
        Assert.assertEquals(3L, DbStatsRegistry.txnCount());
        for (int i = 0; i < 2; i++) {
            DbStatsRegistry.recordTxnLatency(20L);
        }
        Assert.assertEquals(5L, DbStatsRegistry.txnCount());
    }
}
