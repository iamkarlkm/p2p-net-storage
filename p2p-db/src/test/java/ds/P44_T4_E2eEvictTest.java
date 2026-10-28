package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.layered.LayeredIndexExporter;
import com.q3lives.ds.database.fulltext.layered.LayeredInvertedView;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;

/**
 * P44 T4：E2E + 跨实例 / 持久性，3 TR 0F0E。
 *
 * <p>
 * TR4.1 真实 {@link FulltextStore}（独立 temp home）ingest → export → attach → evict → search
 * 与「未 attach 基线」全等；detach 后仍全等
 * <br>TR4.2 跨实例：{@code close()} 后新 {@link FulltextStore} + 新视图 attach + evict →
 * {@code searchByUidsForTest} 与旧实例全等
 * <br>TR4.3 驱逐态直接 {@code close()} → 重开新实例（数据留盘可重建，持久性 0 损）
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P44_T4_E2eEvictTest {

    private static final String[] VOCAB = {"alpha", "beta", "gamma", "delta"};

    private File root;
    private FulltextStore ft;
    private DsIndexSegmentStore segStore;

    @Before
    public void setUp() throws Exception {
        root = new File(System.getProperty("java.io.tmpdir"), "p44t4-" + System.nanoTime());
        root.mkdirs();
        ft = new FulltextStore(root.getAbsolutePath());
        segStore = new DsIndexSegmentStore(root.getAbsolutePath());
    }

    @After
    public void tearDown() {
        try {
            ft.close();
        } catch (Exception ignored) {
            // 清理
        }
        try {
            segStore.close();
        } catch (Exception ignored) {
            // 清理
        }
        deleteRecursive(root);
    }

    /* -------- TR4.1 E2E 读路径切换（evict） -------- */

    @Test(timeout = 120000)
    public void tr41_e2eEvictAndDetach() throws Exception {
        ingestCorpus(ft);
        ft.recomputeIdfForTerms(uidsOf(ft));

        SearchOptions opts = SearchOptions.defaults();
        List<ScoredDocHit> base = ft.search("alpha beta", 10, opts);
        Assert.assertFalse(base.isEmpty());

        new LayeredIndexExporter().export(ft, segStore, 10L);
        try (LayeredInvertedView view = LayeredInvertedView.open(segStore, new long[]{10L})) {
            ft.attachLayeredView(view);
            ft.evictLayeredStats();
            Assert.assertTrue(ft.isLayeredStatsEvicted());
            assertSameHits("tr41-evicted", base, ft.search("alpha beta", 10, opts));
            ft.detachLayeredView();
        }
        assertSameHits("tr41-detached", base, ft.search("alpha beta", 10, opts));
    }

    /* -------- TR4.2 跨实例 -------- */

    @Test(timeout = 120000)
    public void tr42_crossInstanceEquivalence() throws Exception {
        ingestCorpus(ft);
        ft.recomputeIdfForTerms(uidsOf(ft));
        List<Long> qUids = uidsOf(ft);
        SearchOptions opts = SearchOptions.defaults();

        new LayeredIndexExporter().export(ft, segStore, 11L);

        List<ScoredDocHit> old;
        try (LayeredInvertedView v1 = LayeredInvertedView.open(segStore, new long[]{11L})) {
            ft.attachLayeredView(v1);
            ft.evictLayeredStats();
            old = ft.searchByUidsForTest(qUids, 10, opts);
            ft.detachLayeredView();
        }
        Assert.assertFalse("旧实例应召回", old.isEmpty());
        ft.close();

        FulltextStore ft2 = new FulltextStore(root.getAbsolutePath());
        DsIndexSegmentStore seg2 = new DsIndexSegmentStore(root.getAbsolutePath());
        try (LayeredInvertedView v2 = LayeredInvertedView.open(seg2, new long[]{11L})) {
            ft2.attachLayeredView(v2);
            ft2.evictLayeredStats();
            assertEquals("tr42-cross-instance", old, ft2.searchByUidsForTest(qUids, 10, opts));
            ft2.detachLayeredView();
        } finally {
            try {
                ft2.close();
            } catch (Exception ignored) {
                // 清理
            }
            try {
                seg2.close();
            } catch (Exception ignored) {
                // 清理
            }
        }
    }

    /* -------- TR4.3 驱逐态 close → 重开仍可读 -------- */

    @Test(timeout = 120000)
    public void tr43_evictThenCloseAndReopenStillReadable() throws Exception {
        ingestCorpus(ft);
        ft.recomputeIdfForTerms(uidsOf(ft));

        SearchOptions opts = SearchOptions.defaults();
        List<ScoredDocHit> base = ft.search("alpha beta", 10, opts);
        Assert.assertFalse(base.isEmpty());

        new LayeredIndexExporter().export(ft, segStore, 12L);
        try (LayeredInvertedView view = LayeredInvertedView.open(segStore, new long[]{12L})) {
            ft.attachLayeredView(view);
            ft.evictLayeredStats();
            Assert.assertTrue(ft.isLayeredStatsEvicted());
            // 驱逐态直接 close（映射已卸载 → 不崩、不丢数据）
            ft.close();
        }

        FulltextStore ft2 = new FulltextStore(root.getAbsolutePath());
        try {
            assertSameHits("tr43-reopen", base, ft2.search("alpha beta", 10, opts));
        } finally {
            try {
                ft2.close();
            } catch (Exception ignored) {
                // 清理
            }
        }
    }

    /* -------- helpers -------- */

    private static void ingestCorpus(FulltextStore store) throws Exception {
        store.ingestDoc(1L, "alpha beta gamma");
        store.ingestDoc(2L, "alpha beta delta");
        store.ingestDoc(3L, "beta gamma delta");
        store.ingestDoc(4L, "alpha gamma delta");
    }

    private static List<Long> uidsOf(FulltextStore store) throws Exception {
        List<Long> out = new ArrayList<>();
        for (String t : VOCAB) {
            long u = store.termDictToUidForTest(t, false);
            if (u > 0L) {
                out.add(u);
            }
        }
        return out;
    }

    private static void assertEquals(String tag, List<ScoredDocHit> a, List<ScoredDocHit> b) {
        Assert.assertEquals(tag + " 命中数", a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            ScoredDocHit x = a.get(i);
            ScoredDocHit y = b.get(i);
            Assert.assertEquals(tag + " docId@" + i, x.docId, y.docId);
            Assert.assertEquals(tag + " parentId@" + i, x.parentId, y.parentId);
            Assert.assertEquals(tag + " level@" + i, x.level, y.level);
            Assert.assertEquals(tag + " seq@" + i, x.seq, y.seq);
            Assert.assertEquals(tag + " unitId@" + i, x.unitId, y.unitId);
            Assert.assertEquals(tag + " matchedQueryTerms@" + i, x.matchedQueryTerms, y.matchedQueryTerms);
            Assert.assertEquals(tag + " finalScore@" + i,
                    Double.doubleToLongBits(x.finalScore), Double.doubleToLongBits(y.finalScore));
            Assert.assertEquals(tag + " sortScore@" + i,
                    Double.doubleToLongBits(x.sortScore), Double.doubleToLongBits(y.sortScore));
            Assert.assertEquals(tag + " rrfScore@" + i,
                    Double.doubleToLongBits(x.rrfScore), Double.doubleToLongBits(y.rrfScore));
            Assert.assertEquals(tag + " phraseScore@" + i,
                    Double.doubleToLongBits(x.phraseBoostScore), Double.doubleToLongBits(y.phraseBoostScore));
        }
    }

    private static void assertSameHits(String tag, List<ScoredDocHit> a, List<ScoredDocHit> b) {
        assertEquals(tag, a, b);
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
