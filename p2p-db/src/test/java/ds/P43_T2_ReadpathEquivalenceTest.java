package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * P43 T2：切换 bit 级等价性（R7），6 TR 0F0E。
 *
 * <p>
 * 忠实快照视图 attach 后 vs 未 attach 逐字段全等（docId 序 + 4 分数 {@code doubleToLongBits} +
 * {@code unitId} + {@code matchedQueryTerms}）：
 * <br>TR2.1 单 term K=10
 * <br>TR2.2 多 term
 * <br>TR2.3 {@code searchByUidsForTest}
 * <br>TR2.4 {@code phraseBoost=true} + {@code neighborRecallD>0}
 * <br>TR2.5 无命中 query 双空
 * <br>TR2.6 {@code idfOverrideByUnitId} 路径
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P43_T2_ReadpathEquivalenceTest {

    private static final long SEG = 10L;
    private static final String[] VOCAB = {"alpha", "beta", "gamma", "delta"};

    private File root;
    private FulltextStore ft;
    private DsIndexSegmentStore segStore;

    @Before
    public void setUp() throws Exception {
        root = new File(System.getProperty("java.io.tmpdir"), "p43t2-" + System.nanoTime());
        root.mkdirs();
        ft = new FulltextStore(root.getAbsolutePath());
        segStore = new DsIndexSegmentStore(root.getAbsolutePath());
        ft.ingestDoc(1L, "alpha beta gamma");
        ft.ingestDoc(2L, "alpha beta delta");
        ft.ingestDoc(3L, "beta gamma delta");
        ft.ingestDoc(4L, "alpha gamma delta");
        materializeIdf();
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

    /* -------- TR2.1 单 term -------- */

    @Test(timeout = 60000)
    public void tr21_singleTermEquivalence() throws Exception {
        SearchOptions opts = SearchOptions.defaults();
        List<ScoredDocHit> base = ft.search("alpha", 10, opts);
        Assert.assertFalse("基线应有命中", base.isEmpty());
        try (LayeredInvertedView view = exportAndOpen()) {
            Assert.assertTrue("视图应覆盖 alpha", view.hasTerm(uidOf("alpha")));
            ft.attachLayeredView(view);
            List<ScoredDocHit> att = ft.search("alpha", 10, opts);
            assertSameHits("tr21", base, att);
            ft.detachLayeredView();
        }
        assertSameHits("tr21-detach", base, ft.search("alpha", 10, opts));
    }

    /* -------- TR2.2 多 term -------- */

    @Test(timeout = 60000)
    public void tr22_multiTermEquivalence() throws Exception {
        SearchOptions opts = SearchOptions.defaults();
        List<ScoredDocHit> base = ft.search("alpha beta", 10, opts);
        Assert.assertFalse(base.isEmpty());
        try (LayeredInvertedView view = exportAndOpen()) {
            ft.attachLayeredView(view);
            assertSameHits("tr22", base, ft.search("alpha beta", 10, opts));
            ft.detachLayeredView();
        }
    }

    /* -------- TR2.3 searchByUidsForTest -------- */

    @Test(timeout = 60000)
    public void tr23_searchByUidsEquivalence() throws Exception {
        SearchOptions opts = SearchOptions.defaults();
        List<Long> qUids = new ArrayList<>();
        qUids.add(uidOf("beta"));
        qUids.add(uidOf("delta"));
        List<ScoredDocHit> base = ft.searchByUidsForTest(qUids, 10, opts);
        Assert.assertFalse(base.isEmpty());
        try (LayeredInvertedView view = exportAndOpen()) {
            ft.attachLayeredView(view);
            assertSameHits("tr23", base, ft.searchByUidsForTest(qUids, 10, opts));
            ft.detachLayeredView();
        }
    }

    /* -------- TR2.4 phraseBoost + neighborRecall -------- */

    @Test(timeout = 60000)
    public void tr24_phraseBoostAndNeighborRecallEquivalence() throws Exception {
        SearchOptions opts = SearchOptions.defaults();
        opts.phraseBoost = true;
        opts.phraseBoostWeight = 0.2;
        opts.neighborRecallD = 2;
        List<ScoredDocHit> base = ft.search("alpha beta", 10, opts);
        Assert.assertFalse(base.isEmpty());
        try (LayeredInvertedView view = exportAndOpen()) {
            ft.attachLayeredView(view);
            assertSameHits("tr24", base, ft.search("alpha beta", 10, opts));
            ft.detachLayeredView();
        }
    }

    /* -------- TR2.5 无命中 double empty -------- */

    @Test(timeout = 60000)
    public void tr25_noHitBothEmpty() throws Exception {
        SearchOptions opts = SearchOptions.defaults();
        List<ScoredDocHit> base = ft.search("zzzzunknown", 10, opts);
        Assert.assertTrue("无命中基线应为空", base.isEmpty());
        try (LayeredInvertedView view = exportAndOpen()) {
            ft.attachLayeredView(view);
            Assert.assertTrue("挂载后无命中仍空", ft.search("zzzzunknown", 10, opts).isEmpty());
            ft.detachLayeredView();
        }
    }

    /* -------- TR2.6 idfOverride -------- */

    @Test(timeout = 60000)
    public void tr26_idfOverrideEquivalence() throws Exception {
        SearchOptions opts = SearchOptions.defaults();
        Map<Long, Double> override = new HashMap<>();
        override.put(uidOf("alpha"), 3.75);
        opts.idfOverrideByUnitId = override;
        List<ScoredDocHit> base = ft.search("alpha beta", 10, opts);
        Assert.assertFalse(base.isEmpty());
        try (LayeredInvertedView view = exportAndOpen()) {
            ft.attachLayeredView(view);
            assertSameHits("tr26", base, ft.search("alpha beta", 10, opts));
            ft.detachLayeredView();
        }
    }

    /* -------- helpers -------- */

    private long uidOf(String text) throws Exception {
        return ft.termDictToUidForTest(text, false);
    }

    private void materializeIdf() throws Exception {
        List<Long> uids = new ArrayList<>();
        for (String t : VOCAB) {
            long u = ft.termDictToUidForTest(t, false);
            if (u > 0L) {
                uids.add(u);
            }
        }
        ft.recomputeIdfForTerms(uids);
    }

    private LayeredInvertedView exportAndOpen() throws Exception {
        new LayeredIndexExporter().export(ft, segStore, SEG);
        return LayeredInvertedView.open(segStore, new long[]{SEG});
    }

    private static void assertSameHits(String tag, List<ScoredDocHit> a, List<ScoredDocHit> b) {
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
