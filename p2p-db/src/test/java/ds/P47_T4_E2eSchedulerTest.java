package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.compact.SegmentMergeView;
import com.q3lives.ds.database.fulltext.sched.CompactionSchedConfig;
import com.q3lives.ds.database.fulltext.sched.CompactionScheduler;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P47 T4：E2E 多轮收敛 + 限速 + 自适应，4 TR 0F0E。
 *
 * <p>
 * TR4.1 真实 store 多轮 tick 收敛（8→5→2）且合并前后 merge view 查询逐条等价；
 * <br>TR4.2 冷却限速：{@code cooldownNanos} 内二次 tick 返回 0 且 skippedCooldown+1；
 * <br>TR4.3 单 tick 上限限速：双 tier + {@code maxRunsPerTick=1} → 仅执行 1 组、其余记 skippedRateLimit；
 * <br>TR4.4 自适应：段数达水位收紧 {@code fanIn}，使默认配置不触发而自适应配置触发。
 */
public class P47_T4_E2eSchedulerTest {

    private File root;

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p47t4-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    @Test(timeout = 60000)
    public void tr41_multiTickConvergesAndEquivalent() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        for (int i = 0; i < 8; i++) {
            buildSmall(store, 10L + i, i);
        }
        SegmentMergeView pre = new SegmentMergeView(open(store, store.list()));

        CompactionScheduler sched = new CompactionScheduler(store, CompactionSchedConfig.defaults());
        Assert.assertEquals(1, sched.tick());
        Assert.assertEquals("8 → 5", 5, store.list().length);
        Assert.assertEquals(1, sched.tick());
        Assert.assertEquals("5 → 2", 2, store.list().length);
        Assert.assertEquals("2 < 4 不再触发", 0, sched.tick());

        long[] after = store.list();
        try (SegmentMergeView post = new SegmentMergeView(open(store, after))) {
            assertEquivalent(pre, post);
        }
        pre.close();
        sched.close();
    }

    @Test(timeout = 60000)
    public void tr42_cooldownSkip() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        for (int i = 0; i < 4; i++) {
            buildSmall(store, 10L + i, i);
        }
        CompactionSchedConfig cfg = new CompactionSchedConfig(4, 4, 1, 3_600_000_000_000L, Integer.MAX_VALUE);
        CompactionScheduler sched = new CompactionScheduler(store, cfg);
        Assert.assertEquals("首轮执行", 1, sched.tick());
        Assert.assertEquals("冷却内二轮跳过", 0, sched.tick());
        Assert.assertEquals(1L, sched.stats().skippedCooldown());
        Assert.assertEquals(1L, sched.stats().runsSucceeded());
        sched.close();
    }

    @Test(timeout = 60000)
    public void tr43_maxRunsPerTickRateLimit() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        for (int i = 0; i < 4; i++) {
            buildSmall(store, 10L + i, i);
        }
        for (int i = 0; i < 4; i++) {
            buildLarge(store, 20L + i);
        }
        CompactionSchedConfig cfg = new CompactionSchedConfig(4, 4, 1, 0L, Integer.MAX_VALUE);
        CompactionScheduler sched = new CompactionScheduler(store, cfg);
        Assert.assertEquals("上限 1 → 仅执行 1 组", 1, sched.tick());
        Assert.assertEquals(2L, sched.stats().plansSelected());
        Assert.assertEquals(1L, sched.stats().skippedRateLimit());
        Assert.assertEquals("8 → 5", 5, store.list().length);
        sched.close();
    }

    @Test(timeout = 60000)
    public void tr44_adaptiveWatermarkTriggersEarlier() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        for (int i = 0; i < 3; i++) {
            buildSmall(store, 10L + i, i);
        }
        CompactionScheduler noAdapt = new CompactionScheduler(store, CompactionSchedConfig.defaults());
        Assert.assertEquals("默认 fanIn=4：3 段不触发", 0, noAdapt.tick());
        Assert.assertEquals(3, store.list().length);

        CompactionSchedConfig adapt = new CompactionSchedConfig(4, 4, 1, 0L, 3);
        CompactionScheduler sched = new CompactionScheduler(store, adapt);
        Assert.assertEquals("水位=3 收紧 fanIn=3：触发 1 次", 1, sched.tick());
        Assert.assertEquals(1, store.list().length);
        sched.close();
    }

    /* -------- helpers -------- */

    private static final long[] PROBE_DOCS = {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L};
    private static final long[] PROBE_UIDS = {100L, 101L, 102L, 103L, 104L, 105L, 106L, 107L};
    private static final long[] PROBE_TF = {500L, 501L, 502L, 503L, 504L, 505L, 506L, 507L};

    private static void assertEquivalent(SegmentMergeView a, SegmentMergeView b) {
        for (long d : PROBE_DOCS) {
            FulltextStore.WeightDocStatRow ra = a.getDocStat(d);
            FulltextStore.WeightDocStatRow rb = b.getDocStat(d);
            if (ra == null) {
                Assert.assertNull("doc " + d, rb);
            } else {
                Assert.assertNotNull("doc " + d, rb);
                Assert.assertArrayEquals("doc " + d + " docLen", ra.docLen, rb.docLen);
                Assert.assertArrayEquals("doc " + d + " nTerms", ra.nTerms, rb.nTerms);
            }
        }
        for (long u : PROBE_UIDS) {
            FulltextStore.WeightTermStatRow ra = a.getTermStat(u);
            FulltextStore.WeightTermStatRow rb = b.getTermStat(u);
            if (ra == null) {
                Assert.assertNull("uid " + u, rb);
            } else {
                Assert.assertNotNull("uid " + u, rb);
                Assert.assertEquals("uid " + u + " idf", 0, Double.compare(ra.idf, rb.idf));
                Assert.assertArrayEquals("uid " + u + " payload", ra.payload, rb.payload);
            }
        }
        for (long k : PROBE_TF) {
            Assert.assertEquals("tf " + k, a.getTf(k), b.getTf(k));
        }
    }

    private static List<DsIndexSegmentReader> open(DsIndexSegmentStore store, long... segIds) throws Exception {
        List<DsIndexSegmentReader> rs = new ArrayList<>(segIds.length);
        for (long id : segIds) {
            rs.add(store.openReader(id));
        }
        return rs;
    }

    private static void buildSmall(DsIndexSegmentStore store, long segId, int seed) throws Exception {
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(store.segmentFile(segId));
        w.addDocStat(1L + seed, six(1 + seed), six(1));
        w.addTermStat(100L + seed, 1.5 + seed, new byte[]{(byte) (seed + 1)});
        w.addTf(500L + seed, 1 + seed);
        DsIndexSegment s = w.finish();
        store.append(segId, s);
    }

    private static void buildLarge(DsIndexSegmentStore store, long segId) throws Exception {
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(store.segmentFile(segId));
        long x = segId * 0x9E3779B97F4A7C15L + 1L;
        for (int i = 0; i < 800; i++) {
            byte[] payload = new byte[64];
            for (int j = 0; j < payload.length; j++) {
                x ^= x << 13;
                x ^= x >>> 7;
                x ^= x << 17;
                payload[j] = (byte) x;
            }
            w.addTermStat(1000L + i, 1.0 + i, payload);
        }
        DsIndexSegment s = w.finish();
        store.append(segId, s);
    }

    private static int[] six(int v) {
        return new int[]{v, 0, 0, 0, 0, 0};
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
