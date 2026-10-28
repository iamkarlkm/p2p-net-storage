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
 * P44 T3：失效 / 回退语义，3 TR 0F0E。
 *
 * <p>
 * 正确性优先（R8 / §4.1 写路径失效钩子）：
 * <br>TR3.1 驱逐后 {@code ingestDoc} 新文档 → 驱逐态自动失效（{@code isLayeredStatsEvicted()==false}）
 * 且新文档查询正确（WeightStore 映射惰性重建）
 * <br>TR3.2 驱逐后 {@code clearDoc} → 驱逐态自动失效且被删文档不再召回
 * <br>TR3.3 {@code detachLayeredView()} 在驱逐态 → 自动 rehydrate + 后续查询 == 从未 attach
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P44_T3_InvalidateSemanticsTest {

    private static final long SEG = 30L;

    private File root;
    private FulltextStore ft;
    private DsIndexSegmentStore segStore;

    @Before
    public void setUp() throws Exception {
        root = new File(System.getProperty("java.io.tmpdir"), "p44t3-" + System.nanoTime());
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

    /* -------- TR3.1 驱逐后 ingest → 自动失效 + 新文档正确 -------- */

    @Test(timeout = 60000)
    public void tr31_evictThenIngestInvalidatesAndFindsNewDoc() throws Exception {
        ft.ingestDoc(1L, "alpha beta");
        ft.ingestDoc(2L, "alpha gamma");
        ft.recomputeIdfForTerms(uidsOf("alpha", "beta", "gamma"));

        new LayeredIndexExporter().export(ft, segStore, SEG);
        try (LayeredInvertedView view = LayeredInvertedView.open(segStore, new long[]{SEG})) {
            ft.attachLayeredView(view);
            ft.evictLayeredStats();
            Assert.assertTrue("驱逐前置应成立", ft.isLayeredStatsEvicted());

            // 驱逐态写新文档 → 失效钩子应自动清除驱逐态
            ft.ingestDoc(3L, "delta epsilon");
            Assert.assertFalse("写路径应自动清除驱逐态", ft.isLayeredStatsEvicted());
            ft.recomputeIdfForTerms(uidsOf("delta", "epsilon"));

            SearchOptions opts = SearchOptions.defaults();
            List<ScoredDocHit> hits = ft.search("delta", 10, opts);
            Assert.assertFalse("新文档应被 IDX 召回（映射惰性重建）", hits.isEmpty());
            Assert.assertEquals("新 doc3 应在命中集内", 3L, hits.get(0).docId);
            ft.detachLayeredView();
        }
    }

    /* -------- TR3.2 驱逐后 clearDoc → 自动失效 + 被删文档不再召回 -------- */

    @Test(timeout = 60000)
    public void tr32_evictThenClearDocInvalidatesAndDropsDoc() throws Exception {
        ft.ingestDoc(1L, "alpha beta");
        ft.ingestDoc(2L, "alpha gamma");
        ft.recomputeIdfForTerms(uidsOf("alpha", "beta", "gamma"));

        new LayeredIndexExporter().export(ft, segStore, SEG);
        try (LayeredInvertedView view = LayeredInvertedView.open(segStore, new long[]{SEG})) {
            ft.attachLayeredView(view);
            ft.evictLayeredStats();
            Assert.assertTrue("驱逐前置应成立", ft.isLayeredStatsEvicted());

            // 驱逐态删文档 → 失效钩子应自动清除驱逐态
            ft.clearDoc(1L);
            Assert.assertFalse("删路径应自动清除驱逐态", ft.isLayeredStatsEvicted());

            SearchOptions opts = SearchOptions.defaults();
            List<ScoredDocHit> hits = ft.search("beta", 10, opts);
            for (ScoredDocHit h : hits) {
                Assert.assertNotEquals("已删 doc1 不应再召回", 1L, h.docId);
            }
            ft.detachLayeredView();
        }
    }

    /* -------- TR3.3 驱逐态 detach → 自动 rehydrate + 后续 == 从未 attach -------- */

    @Test(timeout = 60000)
    public void tr33_detachWhileEvictedAutoRehydrates() throws Exception {
        ft.ingestDoc(1L, "alpha beta gamma");
        ft.ingestDoc(2L, "alpha beta delta");
        ft.ingestDoc(3L, "beta gamma delta");
        ft.ingestDoc(4L, "alpha gamma delta");
        ft.recomputeIdfForTerms(uidsOf("alpha", "beta", "gamma", "delta"));

        SearchOptions opts = SearchOptions.defaults();
        List<ScoredDocHit> base = ft.search("alpha beta", 10, opts);
        Assert.assertFalse(base.isEmpty());

        new LayeredIndexExporter().export(ft, segStore, SEG);
        try (LayeredInvertedView view = LayeredInvertedView.open(segStore, new long[]{SEG})) {
            ft.attachLayeredView(view);
            ft.evictLayeredStats();
            Assert.assertTrue(ft.isLayeredStatsEvicted());
            ft.detachLayeredView();
            Assert.assertFalse("detach 应自动 rehydrate", ft.isLayeredStatsEvicted());
            Assert.assertFalse("detach 应卸载视图", ft.hasLayeredView());
        }
        // 视图已卸载 + 驱逐态已复位 → 读路径回到 WeightStore（映射由磁盘惰性重建），结果应与从未 attach 全等
        assertSameHits("tr33", base, ft.search("alpha beta", 10, opts));
    }

    /* -------- helpers -------- */

    private List<Long> uidsOf(String... terms) throws Exception {
        List<Long> out = new ArrayList<>();
        for (String t : terms) {
            long u = ft.termDictToUidForTest(t, false);
            if (u > 0L) {
                out.add(u);
            }
        }
        return out;
    }

    private static void assertSameHits(String tag, List<ScoredDocHit> a, List<ScoredDocHit> b) {
        Assert.assertEquals(tag + " 命中数", a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            ScoredDocHit x = a.get(i);
            ScoredDocHit y = b.get(i);
            Assert.assertEquals(tag + " docId@" + i, x.docId, y.docId);
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
