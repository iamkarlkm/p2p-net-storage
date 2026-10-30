package ds;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.autocompact.AutoCompactionStats;

/**
 * P48 T2：{@code AutoCompactionStats}（可观测计数），4 TR 0F0E。
 *
 * <p>
 * TR2.1 初值全 0；TR2.2 逐项累加；TR2.3 {@code resetForTest} 归零；TR2.4 计数相互独立。
 */
public class P48_T2_StatsTest {

    @Test
    public void tr21_initialAllZero() {
        AutoCompactionStats s = new AutoCompactionStats();
        Assert.assertEquals(0L, s.writesObserved());
        Assert.assertEquals(0L, s.triggersFired());
        Assert.assertEquals(0L, s.triggersSkipped());
    }

    @Test
    public void tr22_accumulate() {
        AutoCompactionStats s = new AutoCompactionStats();
        s.recordWrite();
        s.recordWrite();
        s.recordWrite();
        s.recordTrigger();
        s.recordSkip();

        Assert.assertEquals(3L, s.writesObserved());
        Assert.assertEquals(1L, s.triggersFired());
        Assert.assertEquals(1L, s.triggersSkipped());
    }

    @Test
    public void tr23_resetForTest() {
        AutoCompactionStats s = new AutoCompactionStats();
        s.recordWrite();
        s.recordTrigger();
        s.recordSkip();

        s.resetForTest();
        Assert.assertEquals(0L, s.writesObserved());
        Assert.assertEquals(0L, s.triggersFired());
        Assert.assertEquals(0L, s.triggersSkipped());
    }

    @Test
    public void tr24_countersIndependent() {
        AutoCompactionStats s = new AutoCompactionStats();
        s.recordWrite();
        s.recordWrite();
        Assert.assertEquals("写入观察不污染触发/跳过计数", 0L, s.triggersFired());
        Assert.assertEquals(0L, s.triggersSkipped());

        s.recordSkip();
        s.recordSkip();
        Assert.assertEquals("跳过不污染触发计数", 0L, s.triggersFired());
        Assert.assertEquals(2L, s.triggersSkipped());
        Assert.assertEquals("跳过不污染写入计数", 2L, s.writesObserved());
    }
}
