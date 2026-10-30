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
 * P47 T3：{@code CompactionScheduler.tick()} 主路径，5 TR 0F0E。
 *
 * <p>
 * TR3.1 空段表 → 0；TR3.2 单段未达阈值 → 0；TR3.3 单组同层合并 1 次（栈计数核对）；
 * <br>TR3.4 双 tier → 单 tick 两组（maxRunsPerTick=2）合并 2 次；TR3.5 tick 产物与 P40 手工
 * policy→plan→runner 路径查询等价。
 */
public class P47_T3_SchedulerTickTest {

    private File root;

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p47t3-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    @Test(timeout = 60000)
    public void tr31_emptyStoreReturnsZero() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        CompactionScheduler sched = new CompactionScheduler(store, CompactionSchedConfig.defaults());
        Assert.assertEquals(0, sched.tick());
        Assert.assertEquals("tick 入口计数 +1", 1L, sched.stats().ticks());
        Assert.assertEquals(0L, sched.stats().plansSelected());
        sched.close();
    }

    @Test(timeout = 60000)
    public void tr32_singleSegmentNoCandidate() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        buildSmall(store, 10L, 0);
        CompactionScheduler sched = new CompactionScheduler(store, CompactionSchedConfig.defaults());
        Assert.assertEquals("单段 < fanIn=4 不触发", 0, sched.tick());
        Assert.assertEquals(1, store.list().length);
        Assert.assertEquals(0L, sched.stats().plansSelected());
        Assert.assertEquals(0L, sched.stats().runsSucceeded());
        sched.close();
    }

    @Test(timeout = 60000)
    public void tr33_singleGroupMergesOnce() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        for (int i = 0; i < 4; i++) {
            buildSmall(store, 10L + i, i);
        }
        CompactionScheduler sched = new CompactionScheduler(store, CompactionSchedConfig.defaults());
        Assert.assertEquals("4 段同层 → 合并 1 次", 1, sched.tick());
        Assert.assertEquals(1, store.list().length);
        Assert.assertEquals(4L, sched.stats().segmentsMerged());
        Assert.assertEquals(1L, sched.stats().runsSucceeded());
        Assert.assertEquals(1L, sched.stats().plansSelected());
        Assert.assertTrue("归并字节数 > 0", sched.stats().mergedBytes() > 0L);
        sched.close();
    }

    @Test(timeout = 60000)
    public void tr34_multiGroupByTier() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        for (int i = 0; i < 4; i++) {
            buildSmall(store, 10L + i, i);
        }
        for (int i = 0; i < 4; i++) {
            buildLarge(store, 20L + i);
        }
        CompactionSchedConfig cfg = new CompactionSchedConfig(4, 4, 2, 0L, Integer.MAX_VALUE);
        CompactionScheduler sched = new CompactionScheduler(store, cfg);
        Assert.assertEquals("双 tier → 单 tick 合并 2 次", 2, sched.tick());
        Assert.assertEquals("剩余 2 个产物段", 2, store.list().length);
        Assert.assertEquals(2L, sched.stats().runsSucceeded());
        Assert.assertEquals(8L, sched.stats().segmentsMerged());
        sched.close();
    }

    @Test(timeout = 60000)
    public void tr35_equivalentToManualPipeline() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        buildSmall(store, 10L, 0);
        buildSmall(store, 11L, 1);
        buildSmall(store, 12L, 2);
        buildSmall(store, 13L, 3);

        SegmentMergeView pre = new SegmentMergeView(open(store, 10L, 11L, 12L, 13L));
        CompactionScheduler sched = new CompactionScheduler(store, CompactionSchedConfig.defaults());
        Assert.assertEquals(1, sched.tick());

        long[] after = store.list();
        Assert.assertEquals(1, after.length);
        try (SegmentMergeView post = new SegmentMergeView(open(store, after[0]))) {
            assertEquivalent(pre, post);
        }
        pre.close();
        sched.close();
    }

    /* -------- helpers -------- */

    private static final long[] PROBE_DOCS = {1L, 2L, 3L, 4L};
    private static final long[] PROBE_UIDS = {100L, 200L, 300L, 400L};
    private static final long[] PROBE_TF = {500L, 600L, 700L, 800L};

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

    /** 小段（数条记录，tier 6）。 */
    private static void buildSmall(DsIndexSegmentStore store, long segId, int seed) throws Exception {
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(store.segmentFile(segId));
        w.addDocStat(1L + seed, six(1 + seed), six(1));
        w.addTermStat(100L + seed, 1.5 + seed, new byte[]{(byte) (seed + 1)});
        w.addTf(500L + seed, 1 + seed);
        DsIndexSegment s = w.finish();
        store.append(segId, s);
    }

    /** 大段（大量不可压缩变长负载，tier ≥ 7）。 */
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
