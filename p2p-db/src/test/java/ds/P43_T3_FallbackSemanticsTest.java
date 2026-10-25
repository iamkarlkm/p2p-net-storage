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
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P43 T3：回退语义确定性（R8），3 TR 0F0E。
 *
 * <p>
 * TR3.1 陈旧视图（export 后再 {@code ingestDoc} 新文档）→ 新 term / 新 doc 经回退正确召回与打分
 * <br>TR3.2 视图缺 doc/term（{@code hasDoc}/{@code hasTerm} 均 false）→ docLen/tf/idf 全回退 WeightStore
 * <br>TR3.3 {@code detach} 后行为 == 从未 attach（幂等回退）
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P43_T3_FallbackSemanticsTest {

    private File root;
    private FulltextStore ft;
    private DsIndexSegmentStore segStore;

    @Before
    public void setUp() throws Exception {
        root = new File(System.getProperty("java.io.tmpdir"), "p43t3-" + System.nanoTime());
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

    /* -------- TR3.1 陈旧视图 -------- */

    @Test(timeout = 60000)
    public void tr31_staleViewFallsBackForNewTermAndDoc() throws Exception {
        ft.ingestDoc(1L, "alpha beta");
        ft.ingestDoc(2L, "alpha gamma");
        ft.recomputeIdfForTerms(uidsOf("alpha", "beta", "gamma"));

        new LayeredIndexExporter().export(ft, segStore, 10L);
        try (LayeredInvertedView view = LayeredInvertedView.open(segStore, new long[]{10L})) {
            // 视图定格于「仅 doc1/doc2」的快照
            ft.ingestDoc(3L, "delta epsilon");           // 新 doc，视图未覆盖
            ft.recomputeIdfForTerms(uidsOf("delta", "epsilon"));

            Assert.assertFalse("视图不应覆盖新 doc3", view.hasDoc(3L));
            Assert.assertFalse("视图不应覆盖新 term delta", view.hasTerm(uid("delta")));

            SearchOptions opts = SearchOptions.defaults();
            List<ScoredDocHit> base = ft.search("delta", 10, opts);   // 未挂载基线
            Assert.assertFalse("新 doc3 应被 IDX 召回", base.isEmpty());
            Assert.assertEquals("新 doc3 应在命中集内", 3L, base.get(0).docId);

            ft.attachLayeredView(view);
            assertSameHits("tr31", base, ft.search("delta", 10, opts));
            ft.detachLayeredView();
        }
    }

    /* -------- TR3.2 视图缺 doc/term → 全回退 -------- */

    @Test(timeout = 60000)
    public void tr32_viewMissingDocAndTermFallsBack() throws Exception {
        ft.ingestDoc(1L, "alpha beta");
        ft.ingestDoc(2L, "alpha gamma");
        ft.recomputeIdfForTerms(uidsOf("alpha", "beta", "gamma"));

        SearchOptions opts = SearchOptions.defaults();
        List<ScoredDocHit> base = ft.search("alpha", 10, opts);
        Assert.assertFalse(base.isEmpty());

        // 与真实语料完全不同 id 的段：视图对真实 doc/term 全 miss
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(segStore.segmentFile(7L));
        w.addDocStat(9001L, new int[]{5, 0, 0, 0, 0, 0}, new int[]{2, 0, 0, 0, 0, 0});
        w.addTermStat(9002L, 2.5, new byte[]{9});
        w.addTf(9003L, 4);
        DsIndexSegment seg = w.finish();
        segStore.append(7L, seg);

        try (LayeredInvertedView view = LayeredInvertedView.open(segStore, new long[]{7L})) {
            Assert.assertFalse(view.hasDoc(1L));
            Assert.assertFalse(view.hasTerm(uid("alpha")));
            ft.attachLayeredView(view);
            assertSameHits("tr32", base, ft.search("alpha", 10, opts));
            ft.detachLayeredView();
        }
    }

    /* -------- TR3.3 detach 幂等回退 -------- */

    @Test(timeout = 60000)
    public void tr33_detachEqualsNeverAttached() throws Exception {
        ft.ingestDoc(1L, "alpha beta gamma");
        ft.ingestDoc(2L, "alpha beta delta");
        ft.recomputeIdfForTerms(uidsOf("alpha", "beta", "gamma", "delta"));

        SearchOptions opts = SearchOptions.defaults();
        List<ScoredDocHit> base = ft.search("alpha beta", 10, opts);
        Assert.assertFalse(base.isEmpty());

        new LayeredIndexExporter().export(ft, segStore, 20L);
        try (LayeredInvertedView view = LayeredInvertedView.open(segStore, new long[]{20L})) {
            ft.attachLayeredView(view);
            assertSameHits("tr33-attached", base, ft.search("alpha beta", 10, opts));
            ft.detachLayeredView();
            Assert.assertFalse(ft.hasLayeredView());
            assertSameHits("tr33-detached", base, ft.search("alpha beta", 10, opts));
        }
    }

    /* -------- helpers -------- */

    private long uid(String text) throws Exception {
        return ft.termDictToUidForTest(text, false);
    }

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
