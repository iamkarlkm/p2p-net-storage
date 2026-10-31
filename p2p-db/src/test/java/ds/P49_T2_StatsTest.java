package ds;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.autoseg.AutoSegmentStats;

/**
 * P49 T2：{@code AutoSegmentStats}（可观测计数），4 TR 0F0E。
 *
 * <p>
 * TR2.1 初值全 0；TR2.2 逐项累加；TR2.3 {@code resetForTest} 归零；TR2.4 计数相互独立。
 */
public class P49_T2_StatsTest {

    @Test
    public void tr21_initialAllZero() {
        AutoSegmentStats s = new AutoSegmentStats();
        Assert.assertEquals(0L, s.writesObserved());
        Assert.assertEquals(0L, s.flushesFired());
        Assert.assertEquals(0L, s.compactionsFired());
        Assert.assertEquals(0L, s.compactionsSkipped());
    }

    @Test
    public void tr22_accumulate() {
        AutoSegmentStats s = new AutoSegmentStats();
        s.recordWrite();
        s.recordWrite();
        s.recordWrite();
        s.recordFlush();
        s.recordFlush();
        s.recordCompact();
        s.recordSkip();

        Assert.assertEquals(3L, s.writesObserved());
        Assert.assertEquals(2L, s.flushesFired());
        Assert.assertEquals(1L, s.compactionsFired());
        Assert.assertEquals(1L, s.compactionsSkipped());
    }

    @Test
    public void tr23_resetForTest() {
        AutoSegmentStats s = new AutoSegmentStats();
        s.recordWrite();
        s.recordFlush();
        s.recordCompact();
        s.recordSkip();

        s.resetForTest();
        Assert.assertEquals(0L, s.writesObserved());
        Assert.assertEquals(0L, s.flushesFired());
        Assert.assertEquals(0L, s.compactionsFired());
        Assert.assertEquals(0L, s.compactionsSkipped());
    }

    @Test
    public void tr24_countersIndependent() {
        AutoSegmentStats s = new AutoSegmentStats();
        s.recordWrite();
        s.recordWrite();
        Assert.assertEquals("写入观察不污染导出计数", 0L, s.flushesFired());
        Assert.assertEquals("写入观察不污染合并计数", 0L, s.compactionsFired());

        s.recordFlush();
        Assert.assertEquals("导出不污染写入计数", 2L, s.writesObserved());
        Assert.assertEquals("导出不污染合并计数", 0L, s.compactionsFired());
        Assert.assertEquals(0L, s.compactionsSkipped());

        s.recordSkip();
        s.recordSkip();
        Assert.assertEquals("跳过不污染合并计数", 0L, s.compactionsFired());
        Assert.assertEquals(2L, s.compactionsSkipped());
        Assert.assertEquals("跳过不污染导出计数", 1L, s.flushesFired());

        s.recordCompact();
        Assert.assertEquals(1L, s.compactionsFired());
        Assert.assertEquals("合并不污染跳过计数", 2L, s.compactionsSkipped());
    }
}
