package ds;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.autoseg.AutoSegmentPolicy;

/**
 * P49 T1：{@code AutoSegmentPolicy}（双阈值 + 纯函数判定），5 TR 0F0E。
 *
 * <p>
 * TR1.1 2 IAE 守卫；TR1.2 {@code defaults()} 常量；TR1.3 {@code shouldFlush} 边界（&lt;、=、&gt;）；
 * <br>TR1.4 {@code shouldCompact} 边界；TR1.5 边界相等触发 + 纯函数确定性。
 */
public class P49_T1_PolicyTest {

    @Test
    public void tr11_twoGuards() {
        expectIae(() -> new AutoSegmentPolicy(0, 4));   // writesPerFlush < 1
        expectIae(() -> new AutoSegmentPolicy(4, 1));   // minSegmentsToCompact < 2
        expectIae(() -> new AutoSegmentPolicy(-3, 4));
        expectIae(() -> new AutoSegmentPolicy(4, 0));
    }

    @Test
    public void tr12_defaults() {
        AutoSegmentPolicy d = AutoSegmentPolicy.defaults();
        Assert.assertEquals(4, d.writesPerFlush());
        Assert.assertEquals(4, d.minSegmentsToCompact());
    }

    @Test
    public void tr13_shouldFlushBoundary() {
        AutoSegmentPolicy p = new AutoSegmentPolicy(4, 4);
        Assert.assertFalse("3 < 4 → 不导出", p.shouldFlush(3));
        Assert.assertTrue("4 == 4 → 导出", p.shouldFlush(4));
        Assert.assertTrue("5 > 4 → 导出", p.shouldFlush(5));
        AutoSegmentPolicy q = new AutoSegmentPolicy(1, 2);
        Assert.assertFalse("下界：0 < 1 → 不导出", q.shouldFlush(0));
        Assert.assertTrue("下界：1 == 1 → 导出", q.shouldFlush(1));
    }

    @Test
    public void tr14_shouldCompactBoundary() {
        AutoSegmentPolicy p = new AutoSegmentPolicy(4, 4);
        Assert.assertFalse("3 < 4 → 不合并", p.shouldCompact(3));
        Assert.assertTrue("4 == 4 → 合并", p.shouldCompact(4));
        Assert.assertTrue("9 > 4 → 合并", p.shouldCompact(9));
        AutoSegmentPolicy q = new AutoSegmentPolicy(1, 2);
        Assert.assertFalse("下界：1 < 2 → 不合并", q.shouldCompact(1));
        Assert.assertTrue("下界：2 == 2 → 合并", q.shouldCompact(2));
    }

    @Test
    public void tr15_boundaryAndPureDeterminism() {
        AutoSegmentPolicy p = new AutoSegmentPolicy(4, 4);
        for (int i = 0; i < 100; i++) {
            Assert.assertTrue("同输入同输出（达标）", p.shouldFlush(4));
            Assert.assertFalse("同输入同输出（不足）", p.shouldFlush(3));
            Assert.assertTrue("同输入同输出（段达标）", p.shouldCompact(4));
            Assert.assertFalse("同输入同输出（段不足）", p.shouldCompact(3));
        }
        Assert.assertEquals("阈值只读不变", 4, p.writesPerFlush());
        Assert.assertEquals("阈值只读不变", 4, p.minSegmentsToCompact());
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
