package ds;

import java.io.File;
import java.lang.reflect.Method;
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
 * P44 T2：驱逐后 bit 级等价性（R7'），6 TR 0F0E。
 *
 * <p>
 * 「驱逐态 + 视图完整」vs「未 attach 基线（WeightStore 口径）」逐字段全等：
 * docId 序 + {@code parentId}/{@code level}/{@code seq}/{@code unitId} +
 * {@code matchedQueryTerms} + 4 分数（{@code Double.doubleToLongBits}）+ {@code avgdl} 同 double 值。
 * <br>TR2.1 单 term K=10（含 avgdl 同值）
 * <br>TR2.2 多 term
 * <br>TR2.3 {@code searchByUidsForTest}
 * <br>TR2.4 {@code phraseBoost=true} + {@code neighborRecallD>0}（positions 未驱逐 ⇒ 0 影响）
 * <br>TR2.5 无命中 query 双空
 * <br>TR2.6 {@code idfOverrideByUnitId} 路径
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P44_T2_EvictedEquivalenceTest {

    private static final long SEG = 10L;
    private static final String[] VOCAB = {"alpha", "beta", "gamma", "delta"};

    private File root;
    private FulltextStore ft;
    private DsIndexSegmentStore segStore;

    @Before
    public void setUp() throws Exception {
        root = new File(System.getProperty("java.io.tmpdir"), "p44t2-" + System.nanoTime());
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

    /* -------- TR2.1 单 term + avgdl 同值 -------- */

    @Test(timeout = 60000)
    public void tr21_singleTermEquivalenceWithAvgdl() throws Exception {
        SearchOptions opts = SearchOptions.defaults();
        double baseAvg = avgDocLen(ft);
        List<ScoredDocHit> base = ft.search("alpha", 10, opts);
        Assert.assertFalse("基线应有命中", base.isEmpty());
        try (LayeredInvertedView view = exportAndOpen()) {
            ft.attachLayeredView(view);
            ft.evictLayeredStats();
            Assert.assertTrue("应处于驱逐态", ft.isLayeredStatsEvicted());
            assertSameHits("tr21", base, ft.search("alpha", 10, opts));
            Assert.assertEquals("tr21 avgdl 应同 double 值",
                    Double.doubleToLongBits(baseAvg), Double.doubleToLongBits(avgDocLen(ft)));
            ft.detachLayeredView();
        }
    }

    /* -------- TR2.2 多 term -------- */

    @Test(timeout = 60000)
    public void tr22_multiTermEquivalence() throws Exception {
        SearchOptions opts = SearchOptions.defaults();
        double baseAvg = avgDocLen(ft);
        List<ScoredDocHit> base = ft.search("alpha beta", 10, opts);
        Assert.assertFalse(base.isEmpty());
        try (LayeredInvertedView view = exportAndOpen()) {
            ft.attachLayeredView(view);
            ft.evictLayeredStats();
            assertSameHits("tr22", base, ft.search("alpha beta", 10, opts));
            Assert.assertEquals("tr22 avgdl 应同 double 值",
                    Double.doubleToLongBits(baseAvg), Double.doubleToLongBits(avgDocLen(ft)));
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
            ft.evictLayeredStats();
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
            ft.evictLayeredStats();
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
            ft.evictLayeredStats();
            Assert.assertTrue("驱逐后无命中仍空", ft.search("zzzzunknown", 10, opts).isEmpty());
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
            ft.evictLayeredStats();
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

    /** 反射调 package-private 的 computeAvgDocLen（WORD 层），用于 R7' avgdl 同值断言。 */
    private static double avgDocLen(FulltextStore store) throws Exception {
        Method m = FulltextStore.class.getDeclaredMethod("computeAvgDocLen", int.class);
        m.setAccessible(true);
        return (Double) m.invoke(store, FulltextStore.DS1_WORD);
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
