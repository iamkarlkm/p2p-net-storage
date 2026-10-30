package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.autocompact.AutoCompactionHook;
import com.q3lives.ds.database.fulltext.autocompact.AutoCompactionPolicy;
import com.q3lives.ds.database.fulltext.compact.SegmentMergeView;
import com.q3lives.ds.database.fulltext.sched.CompactionSchedConfig;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P48 T4：E2E 自动挂钩（真实 store 多次 append → 自动合并收敛 + 等价 + 计数 + 幂等），4 TR 0F0E。
 *
 * <p>
 * TR4.1 多轮 {@code hook.append} 触发自动合并、{@code list()} 收敛且计数正确；
 * <br>TR4.2 自动触发合并产物与合并前查询逐条等价；<br>TR4.3 写入达标但段数不足 → {@code triggersSkipped}
 * 累计且不触发；<br>TR4.4 {@code close()} 幂等。
 */
public class P48_T4_E2eAutoHookTest {

    private File root;

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p48t4-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    @Test(timeout = 60000)
    public void tr41_autoMergeConvergesAndCounts() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "t41").getAbsolutePath());
        AutoCompactionHook hook = new AutoCompactionHook(store, CompactionSchedConfig.defaults(),
                AutoCompactionPolicy.defaults());
        // 段 id 间隔 100：避开调度器产物 id = max(segId)+1 的占位
        long[] ids = {100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L};
        for (int i = 0; i < ids.length; i++) {
            hook.append(ids[i], writeSmall(store, ids[i], i));
        }
        Assert.assertEquals("8 次写入观察", 8L, hook.autoStats().writesObserved());
        Assert.assertEquals("两轮自动触发（4→1 各一次）", 2L, hook.autoStats().triggersFired());
        Assert.assertEquals(0L, hook.autoStats().triggersSkipped());
        Assert.assertEquals("两轮成功合并", 2L, hook.stats().runsSucceeded());
        Assert.assertEquals("list() 收敛", 2, store.list().length);
        Assert.assertEquals("触发后写计数复位", 0, hook.writesSinceLastTick());
        hook.close();
    }

    @Test(timeout = 60000)
    public void tr42_autoMergedEquivalentToSources() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "t42").getAbsolutePath());
        long[] ids = {100L, 200L, 300L, 400L};
        List<DsIndexSegment> segs = new ArrayList<>(ids.length);
        for (int i = 0; i < ids.length; i++) {
            segs.add(writeSmall(store, ids[i], i));
        }
        // 合并前视图：直读 4 个源段文件（登记前即可读，覆盖全部将被合并的源段）
        List<DsIndexSegmentReader> readers = new ArrayList<>(ids.length);
        for (long id : ids) {
            readers.add(DsIndexSegmentReader.open(store.segmentFile(id)));
        }
        SegmentMergeView pre = new SegmentMergeView(readers);

        // writesPerTrigger=1：第 4 次 append 即触发；minSegmentsToCompact=4：段齐 4 才合并
        AutoCompactionHook hook = new AutoCompactionHook(store, CompactionSchedConfig.defaults(),
                new AutoCompactionPolicy(1, 4));
        for (int i = 0; i < ids.length; i++) {
            hook.append(ids[i], segs.get(i));
        }
        Assert.assertEquals("四合一 → 1 段", 1, store.list().length);

        try (SegmentMergeView post = new SegmentMergeView(open(store, store.list()))) {
            assertEquivalent(pre, post);
        }
        pre.close();
        hook.close();
    }

    @Test(timeout = 60000)
    public void tr43_skippedWhenTooFewSegments() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "t43").getAbsolutePath());
        // 写入阈值 1 但段数下限 10：每次都「写入达标但段不足」→ 跳过
        AutoCompactionHook hook = new AutoCompactionHook(store, CompactionSchedConfig.defaults(),
                new AutoCompactionPolicy(1, 10));
        for (int i = 0; i < 4; i++) {
            long segId = 100L * (i + 1);
            hook.append(segId, writeSmall(store, segId, i));
        }
        Assert.assertEquals("每次写入均因段数不足跳过", 4L, hook.autoStats().triggersSkipped());
        Assert.assertEquals(0L, hook.autoStats().triggersFired());
        Assert.assertEquals("未触发 → 0 轮 tick", 0L, hook.stats().ticks());
        Assert.assertEquals(4, store.list().length);
        Assert.assertEquals("跳过不复位写计数", 4, hook.writesSinceLastTick());
        hook.close();
    }

    @Test(timeout = 60000)
    public void tr44_closeIdempotent() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "t44").getAbsolutePath());
        AutoCompactionHook hook = new AutoCompactionHook(store, CompactionSchedConfig.defaults(),
                AutoCompactionPolicy.defaults());
        hook.append(100L, writeSmall(store, 100L, 0));
        hook.close();
        hook.close();
        hook.close();
        Assert.assertEquals(1, store.list().length);
    }

    /* -------- helpers -------- */
    private static final long[] PROBE_DOCS = {1L, 2L, 3L, 4L};
    private static final long[] PROBE_UIDS = {100L, 101L, 102L, 103L};
    private static final long[] PROBE_TF = {500L, 501L, 502L, 503L};

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

    /**
     * 写一个小段文件（不登记），返回其描述对象。
     */
    private static DsIndexSegment writeSmall(DsIndexSegmentStore store, long segId, int seed) throws Exception {
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(store.segmentFile(segId));
        w.addDocStat(1L + seed, six(1 + seed), six(1));
        w.addTermStat(100L + seed, 1.5 + seed, new byte[]{(byte) (seed + 1)});
        w.addTf(500L + seed, 1 + seed);
        return w.finish();
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
