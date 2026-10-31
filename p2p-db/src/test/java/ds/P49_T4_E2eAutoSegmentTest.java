package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.autoseg.AutoSegmentHook;
import com.q3lives.ds.database.fulltext.autoseg.AutoSegmentPolicy;
import com.q3lives.ds.database.fulltext.compact.SegmentMergeView;
import com.q3lives.ds.database.fulltext.sched.CompactionSchedConfig;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;

/**
 * P49 T4：E2E 写路径自动段化（真实 {@code FulltextStore} 多次写 → 自动产段 + 段数达阈值自动合并 +
 * 段视图与内存逐条等价 + 幂等），4 TR 0F0E。
 *
 * <p>
 * TR4.1 多轮 {@code putDoc} 自动产段并触发合并、{@code list()} 收敛且账目守恒；<br>
 * TR4.2 自动导出段（多段叠加视图）与内存 WeightStore 逐条等价；<br>
 * TR4.3 段数不足 → {@code compactionsSkipped} 累计且不合并；<br>TR4.4 {@code close()} 幂等。
 */
public class P49_T4_E2eAutoSegmentTest {

    private static final String TEXT = "hello world autoseg engine";

    private File root;
    private final List<FulltextStore> stores = new ArrayList<>();

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p49t4-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        for (FulltextStore ft : stores) {
            try {
                ft.close();
            } catch (Exception ignore) {
                // best-effort
            }
        }
        deleteRecursive(root);
    }

    @Test(timeout = 180000)
    public void tr41_autoSegmentAndCompactConverge() throws Exception {
        FulltextStore ft = newFt("t41_mem");
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "t41_seg").getAbsolutePath());
        AutoSegmentHook hook = new AutoSegmentHook(ft, store, CompactionSchedConfig.defaults(),
                new AutoSegmentPolicy(1, 4));

        for (int i = 0; i < 8; i++) {
            hook.putDoc(1L, TEXT);
        }
        Assert.assertEquals("8 次写各导 1 段", 8L, hook.stats().flushesFired());
        Assert.assertEquals("账目守恒", hook.stats().flushesFired(),
                hook.stats().compactionsFired() + hook.stats().compactionsSkipped());
        Assert.assertTrue("至少合并过 1 轮，got " + hook.stats().compactionsFired(),
                hook.stats().compactionsFired() >= 1L);
        Assert.assertTrue("list() 未死涨，got " + store.list().length,
                store.list().length <= 4);
        hook.close();
    }

    @Test(timeout = 180000)
    public void tr42_exportedSegmentsEquivalentToMemory() throws Exception {
        FulltextStore ft = newFt("t42_mem");
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "t42_seg").getAbsolutePath());
        // 每次写都导段但不合并 → 累积快照段叠加后应等价内存最终态
        AutoSegmentHook hook = new AutoSegmentHook(ft, store, CompactionSchedConfig.defaults(),
                new AutoSegmentPolicy(1, 1_000));

        hook.putDoc(1L, "alpha beta gamma");
        hook.putDoc(2L, "beta delta epsilon");
        hook.putDoc(3L, "gamma epsilon alpha");
        // termStat（idf）为惰性字段：仅 search / 显式 rebuild 时写入 → 触发一次 search 使其入内存，
        // 再导一张含 termStat 的全量快照段，从而让段视图具备可比对的 termStat 数据。
        ft.search("alpha beta gamma delta epsilon", 10, FulltextStore.SearchOptions.defaults());
        hook.flushNow();
        Assert.assertEquals("4 段已登记（3 写段 + 1 含 termStat 快照段）", 4, store.list().length);

        long[] ids = store.list(); // manifest 插入序 = 旧 → 新
        List<DsIndexSegmentReader> readers = new ArrayList<>(ids.length);
        for (long id : ids) {
            readers.add(store.openReader(id));
        }
        try (SegmentMergeView view = new SegmentMergeView(readers)) {
            int docs = 0;
            for (Long d : ft.weightStoreAllDocKeysForTest()) {
                FulltextStore.WeightDocStatRow truth = ft.getDocStatForTest(d);
                FulltextStore.WeightDocStatRow got = view.getDocStat(d);
                Assert.assertNotNull("doc " + d + " 应命中", got);
                Assert.assertArrayEquals("docLen doc" + d, truth.docLen, got.docLen);
                Assert.assertArrayEquals("nTerms doc" + d, truth.nTerms, got.nTerms);
                docs++;
            }
            Assert.assertEquals("doc 数一致", 3, docs);

            int terms = 0;
            for (Long u : ft.weightStoreAllTermKeys()) {
                FulltextStore.WeightTermStatRow truth = ft.getTermStatForTest(u);
                FulltextStore.WeightTermStatRow got = view.getTermStat(u);
                Assert.assertNotNull("uid " + u + " 应命中", got);
                Assert.assertEquals("idf uid" + u, 0, Double.compare(truth.idf, got.idf));
                Assert.assertArrayEquals("payload uid" + u, truth.payload, got.payload);
                terms++;
            }
            Assert.assertTrue("term 数 > 0", terms > 0);

            int tfs = 0;
            for (java.util.Map.Entry<Long, Integer> e : ft.weightStoreAllTfEntries().entrySet()) {
                Assert.assertEquals("tf key=" + e.getKey(), (int) e.getValue(), view.getTf(e.getKey()));
                tfs++;
            }
            Assert.assertTrue("tf 数 > 0", tfs > 0);
        }
        hook.close();
    }

    @Test(timeout = 180000)
    public void tr43_skipWhenTooFewSegments() throws Exception {
        FulltextStore ft = newFt("t43_mem");
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "t43_seg").getAbsolutePath());
        // 每次写都导段，但段数下限 10 → 每次都「导段成功但段不足」→ 跳过
        AutoSegmentHook hook = new AutoSegmentHook(ft, store, CompactionSchedConfig.defaults(),
                new AutoSegmentPolicy(1, 10));

        for (int i = 1; i <= 4; i++) {
            hook.putDoc(i, TEXT + " " + i);
        }
        Assert.assertEquals(4L, hook.stats().flushesFired());
        Assert.assertEquals("段数不足 → 0 合并", 0L, hook.stats().compactionsFired());
        Assert.assertEquals("每次均记跳过", 4L, hook.stats().compactionsSkipped());
        Assert.assertEquals("未合并 → 0 tick", 0L, hook.compactionStats().ticks());
        Assert.assertEquals(4, store.list().length);
        hook.close();
    }

    @Test(timeout = 120000)
    public void tr44_closeIdempotent() throws Exception {
        FulltextStore ft = newFt("t44_mem");
        DsIndexSegmentStore store = new DsIndexSegmentStore(new File(root, "t44_seg").getAbsolutePath());
        AutoSegmentHook hook = new AutoSegmentHook(ft, store, CompactionSchedConfig.defaults(),
                new AutoSegmentPolicy(1, 1_000));
        hook.putDoc(1L, TEXT);
        hook.close();
        hook.close();
        hook.close();
        Assert.assertEquals(1, store.list().length);
    }

    /* -------- helpers -------- */

    private FulltextStore newFt(String name) throws Exception {
        FulltextStore ft = new FulltextStore(new File(root, name).getAbsolutePath());
        stores.add(ft);
        return ft;
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
