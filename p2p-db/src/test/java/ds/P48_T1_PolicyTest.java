package ds;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.autocompact.AutoCompactionPolicy;

/**
 * P48 T1：{@code AutoCompactionPolicy}（双阈值 + 纯函数判定），5 TR 0F0E。
 *
 * <p>
 * TR1.1 2 IAE 守卫；TR1.2 {@code defaults()} 常量；TR1.3 {@code shouldTrigger} 双达标；
 * <br>TR1.4 {@code shouldTrigger} 单条件不足不触发；TR1.5 边界相等触发 + 纯函数确定性。
 */
public class P48_T1_PolicyTest {

    @Test
    public void tr11_twoGuards() {
        expectIae(() -> new AutoCompactionPolicy(0, 4));   // writesPerTrigger < 1
        expectIae(() -> new AutoCompactionPolicy(4, 1));   // minSegmentsToCompact < 2
    }

    @Test
    public void tr12_defaults() {
        AutoCompactionPolicy d = AutoCompactionPolicy.defaults();
        Assert.assertEquals(4, d.writesPerTrigger());
        Assert.assertEquals(4, d.minSegmentsToCompact());
    }

    @Test
    public void tr13_shouldTriggerBothSatisfied() {
        AutoCompactionPolicy p = new AutoCompactionPolicy(4, 4);
        Assert.assertTrue("写入 5 段数 6 → 触发", p.shouldTrigger(5, 6));
        Assert.assertTrue("写入 10 段数 4 → 触发", p.shouldTrigger(10, 4));
    }

    @Test
    public void tr14_shouldTriggerOneConditionShort() {
        AutoCompactionPolicy p = new AutoCompactionPolicy(4, 4);
        Assert.assertFalse("写入不足 → 不触发", p.shouldTrigger(3, 100));
        Assert.assertFalse("段数不足 → 不触发", p.shouldTrigger(100, 3));
        Assert.assertFalse("双不足 → 不触发", p.shouldTrigger(1, 1));
    }

    @Test
    public void tr15_boundaryAndDeterminism() {
        AutoCompactionPolicy p = new AutoCompactionPolicy(4, 4);
        Assert.assertTrue("边界相等 → 触发", p.shouldTrigger(4, 4));
        for (int i = 0; i < 100; i++) {
            Assert.assertTrue("同输入同输出", p.shouldTrigger(4, 4));
            Assert.assertFalse("同输入同输出（不足）", p.shouldTrigger(3, 4));
        }
        AutoCompactionPolicy q = new AutoCompactionPolicy(1, 2);
        Assert.assertTrue("下界配置：1 写 2 段 → 触发", q.shouldTrigger(1, 2));
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
