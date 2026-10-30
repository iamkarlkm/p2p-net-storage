package ds;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.sched.CompactionStats;

/**
 * P47 T2：{@code CompactionStats}（可观测计数），4 TR 0F0E。
 *
 * <p>
 * TR2.1 初值全 0；TR2.2 逐项累加；TR2.3 {@code resetForTest} 归零；TR2.4 计数相互独立。
 */
public class P47_T2_StatsTest {

    @Test
    public void tr21_initialAllZero() {
        CompactionStats s = new CompactionStats();
        Assert.assertEquals(0L, s.ticks());
        Assert.assertEquals(0L, s.plansSelected());
        Assert.assertEquals(0L, s.runsSucceeded());
        Assert.assertEquals(0L, s.runsFailed());
        Assert.assertEquals(0L, s.skippedCooldown());
        Assert.assertEquals(0L, s.skippedRateLimit());
        Assert.assertEquals(0L, s.segmentsMerged());
        Assert.assertEquals(0L, s.mergedBytes());
    }

    @Test
    public void tr22_accumulate() {
        CompactionStats s = new CompactionStats();
        s.recordTick();
        s.recordTick();
        s.recordPlans(3);
        s.recordRun(4L, 1000L);
        s.recordRun(2L, 500L);
        s.recordRunFailed();
        s.recordSkippedCooldown();
        s.recordSkippedRateLimit(2);

        Assert.assertEquals(2L, s.ticks());
        Assert.assertEquals(3L, s.plansSelected());
        Assert.assertEquals(2L, s.runsSucceeded());
        Assert.assertEquals(1L, s.runsFailed());
        Assert.assertEquals(1L, s.skippedCooldown());
        Assert.assertEquals(2L, s.skippedRateLimit());
        Assert.assertEquals(6L, s.segmentsMerged());
        Assert.assertEquals(1500L, s.mergedBytes());
    }

    @Test
    public void tr23_resetForTest() {
        CompactionStats s = new CompactionStats();
        s.recordTick();
        s.recordPlans(5);
        s.recordRun(3L, 42L);
        s.recordRunFailed();
        s.recordSkippedCooldown();
        s.recordSkippedRateLimit(1);

        s.resetForTest();
        Assert.assertEquals(0L, s.ticks());
        Assert.assertEquals(0L, s.plansSelected());
        Assert.assertEquals(0L, s.runsSucceeded());
        Assert.assertEquals(0L, s.runsFailed());
        Assert.assertEquals(0L, s.skippedCooldown());
        Assert.assertEquals(0L, s.skippedRateLimit());
        Assert.assertEquals(0L, s.segmentsMerged());
        Assert.assertEquals(0L, s.mergedBytes());
    }

    @Test
    public void tr24_countersIndependent() {
        CompactionStats s = new CompactionStats();
        s.recordSkippedCooldown();
        s.recordSkippedCooldown();
        Assert.assertEquals("冷却跳过不污染 tick/选段计数", 0L, s.ticks());
        Assert.assertEquals(0L, s.plansSelected());
        Assert.assertEquals(0L, s.runsSucceeded());
        Assert.assertEquals(2L, s.skippedCooldown());

        s.recordRun(1L, 8L);
        Assert.assertEquals("成功合并不污染失败/跳过计数", 0L, s.runsFailed());
        Assert.assertEquals(0L, s.skippedRateLimit());
        Assert.assertEquals(1L, s.runsSucceeded());
        Assert.assertEquals(1L, s.segmentsMerged());
        Assert.assertEquals(8L, s.mergedBytes());
    }
}
