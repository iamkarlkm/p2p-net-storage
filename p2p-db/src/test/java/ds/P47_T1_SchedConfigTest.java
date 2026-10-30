package ds;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.sched.CompactionSchedConfig;

/**
 * P47 T1：{@code CompactionSchedConfig}（阈值 + 自适应水位 + 双限速），5 TR 0F0E。
 *
 * <p>
 * TR1.1 5 IAE 守卫；TR1.2 {@code defaults()} 常量；TR1.3 {@code effectiveFanIn}
 * 达水位收紧；
 * <br>TR1.4 {@code effectiveFanIn} 未达水位默认；TR1.5 收紧下界钳制 + 确定性。
 */
public class P47_T1_SchedConfigTest {

    @Test
    public void tr11_fiveGuards() {
        expectIae(() -> new CompactionSchedConfig(1, 4, 1, 0L, 10));   // fanIn < 2
        expectIae(() -> new CompactionSchedConfig(4, 3, 1, 0L, 10));   // maxFanIn < fanIn
        expectIae(() -> new CompactionSchedConfig(4, 4, 0, 0L, 10));   // maxRunsPerTick < 1
        expectIae(() -> new CompactionSchedConfig(4, 4, 1, -1L, 10));  // cooldownNanos < 0
        expectIae(() -> new CompactionSchedConfig(4, 4, 1, 0L, 0));    // highWatermarkSegments < 1
    }

    @Test
    public void tr12_defaults() {
        CompactionSchedConfig d = CompactionSchedConfig.defaults();
        Assert.assertEquals(4, d.fanIn());
        Assert.assertEquals(4, d.maxFanIn());
        Assert.assertEquals(1, d.maxRunsPerTick());
        Assert.assertEquals(0L, d.cooldownNanos());
        Assert.assertEquals(Integer.MAX_VALUE, d.highWatermarkSegments());
        Assert.assertEquals("默认配置永不自适应收紧", 4, d.effectiveFanIn(1_000_000));
    }

    @Test
    public void tr13_effectiveFanInAboveWatermark() {
        CompactionSchedConfig c = new CompactionSchedConfig(4, 4, 1, 0L, 10);
        Assert.assertEquals("段数达水位 → 收紧一档", 3, c.effectiveFanIn(10));
        Assert.assertEquals("段数超水位 → 收紧一档", 3, c.effectiveFanIn(99));
    }

    @Test
    public void tr14_effectiveFanInBelowWatermark() {
        CompactionSchedConfig c = new CompactionSchedConfig(4, 4, 1, 0L, 10);
        Assert.assertEquals("段数未达水位 → 保持默认", 4, c.effectiveFanIn(9));
        Assert.assertEquals("空段表 → 保持默认", 4, c.effectiveFanIn(0));
    }

    @Test
    public void tr15_clampAndDeterminism() {
        CompactionSchedConfig c = new CompactionSchedConfig(2, 2, 1, 0L, 1);
        Assert.assertEquals("fanIn=2 收紧下界钳制为 2", 2, c.effectiveFanIn(5));

        CompactionSchedConfig c2 = new CompactionSchedConfig(8, 8, 1, 0L, 4);
        Assert.assertEquals(7, c2.effectiveFanIn(4));
        for (int i = 0; i < 100; i++) {
            Assert.assertEquals("同输入同输出", 7, c2.effectiveFanIn(4));
        }
    }

    private interface ThrowingRunnable {

        void run() throws Exception;
    }

    private static void expectIae(ThrowingRunnable r) {
        try {
            r.run();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        } catch (Exception e) {
            Assert.fail("expected IllegalArgumentException but got: " + e);
        }
    }
}
