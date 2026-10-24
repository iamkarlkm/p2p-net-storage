package ds;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.compact.SegmentCompactionPolicy;

/**
 * P40 T3：SegmentCompactionPolicy（size-tiered 触发策略），3 TR 0F0E。
 *
 * <p>
 * TR3.1 tier 分层（floor(log_4)）+ 层内达 fanIn 触发（跨层只触发达标层）
 * <br>TR3.2 未达 fanIn 不触发 + maxFanIn 截断取最早 + 多组按 tier 升序
 * <br>TR3.3 4 IAE（长度不等 / fanIn&lt;2 / maxFanIn&lt;fanIn / segIds 非严格升序）+ 确定性
 */
public class P40_T3_CompactionPolicyTest {

    @Test
    public void tr31_tierSplitAndTrigger() {
        long[] ids = {10L, 11L, 12L, 13L, 14L};
        long[] bytes = {1L, 2L, 3L, 100L, 200L};
        List<long[]> groups = SegmentCompactionPolicy.selectTiers(ids, bytes, 3, 3);
        Assert.assertEquals(1, groups.size());
        Assert.assertArrayEquals(new long[]{10L, 11L, 12L}, groups.get(0));
    }

    @Test
    public void tr32_belowFanInAndMaxFanInCap() {
        long[] ids = {1L, 2L, 3L, 4L, 5L};
        long[] same = {10L, 10L, 10L, 10L, 10L};
        List<long[]> capped = SegmentCompactionPolicy.selectTiers(ids, same, 3, 3);
        Assert.assertEquals(1, capped.size());
        Assert.assertArrayEquals(new long[]{1L, 2L, 3L}, capped.get(0));

        Assert.assertTrue(SegmentCompactionPolicy.selectTiers(ids, same, 6, 6).isEmpty());

        long[] twoTiers = {1L, 1L, 100L, 100L, 100L};
        List<long[]> ordered = SegmentCompactionPolicy.selectTiers(ids, twoTiers, 2, 2);
        Assert.assertEquals(2, ordered.size());
        Assert.assertArrayEquals(new long[]{1L, 2L}, ordered.get(0));
        Assert.assertArrayEquals(new long[]{3L, 4L}, ordered.get(1));
    }

    @Test
    public void tr33_guardsAndDeterminism() throws Exception {
        long[] ids = {1L, 2L, 3L};
        long[] bytes = {10L, 10L, 10L};

        expectIae(() -> SegmentCompactionPolicy.selectTiers(null, bytes, 2, 2));
        expectIae(() -> SegmentCompactionPolicy.selectTiers(ids, null, 2, 2));
        expectIae(() -> SegmentCompactionPolicy.selectTiers(ids, new long[]{10L, 10L}, 2, 2));
        expectIae(() -> SegmentCompactionPolicy.selectTiers(ids, bytes, 1, 2));
        expectIae(() -> SegmentCompactionPolicy.selectTiers(ids, bytes, 2, 1));
        expectIae(() -> SegmentCompactionPolicy.selectTiers(new long[]{2L, 1L}, new long[]{10L, 10L}, 2, 2));
        expectIae(() -> SegmentCompactionPolicy.selectTiers(new long[]{1L, 1L}, new long[]{10L, 10L}, 2, 2));

        List<long[]> a = SegmentCompactionPolicy.selectTiers(ids, bytes, 2, 2);
        List<long[]> b = SegmentCompactionPolicy.selectTiers(ids, bytes, 2, 2);
        Assert.assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            Assert.assertArrayEquals(a.get(i), b.get(i));
        }
    }

    private interface ThrowingRunnable {

        void run() throws Exception;
    }

    private static void expectIae(ThrowingRunnable r) throws Exception {
        try {
            r.run();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
