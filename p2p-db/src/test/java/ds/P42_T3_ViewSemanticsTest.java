package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.compact.SegmentCompactor;
import com.q3lives.ds.database.fulltext.layered.LayeredInvertedView;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P42 T3：{@link LayeredInvertedView} 只读视图语义，3 TR 0F0E。
 *
 * <p>
 * TR3.1 docLen/nTerms 命中/miss=0 + level 越界 IAE + hasDoc
 * <br>TR3.2 tf 与 P37 reader 全等 + miss=0 + idf/termPayload/hasTerm
 * <br>TR3.3 多段「新段优先」覆盖 + P40 归并后单段等价 + fingerprint 随段序变化 + close 幂等
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P42_T3_ViewSemanticsTest {

    private File root;
    private DsIndexSegmentStore store;

    @Before
    public void setUp() throws Exception {
        root = new File(System.getProperty("java.io.tmpdir"), "p42t3-" + System.nanoTime());
        root.mkdirs();
        store = new DsIndexSegmentStore(root.getAbsolutePath());
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    /* -------- TR3.1 docLen/nTerms -------- */
    @Test(timeout = 60000)
    public void tr31_docLenNTermsHitMissAndLevelGuard() throws Exception {
        build(10L, w -> w.addDocStat(1L, new int[]{11, 12, 13, 14, 15, 16}, new int[]{1, 2, 3, 4, 5, 6}));
        try (LayeredInvertedView view = LayeredInvertedView.open(store, new long[]{10L})) {
            for (int lv = 0; lv < 6; lv++) {
                Assert.assertEquals("docLen doc1 lv" + lv, 11 + lv, view.docLen(1L, lv));
                Assert.assertEquals("nTerms doc1 lv" + lv, 1 + lv, view.nTerms(1L, lv));
            }
            Assert.assertEquals("miss doc → docLen 0", 0, view.docLen(99L, 0));
            Assert.assertEquals("miss doc → nTerms 0", 0, view.nTerms(99L, 3));
            Assert.assertTrue(view.hasDoc(1L));
            Assert.assertFalse(view.hasDoc(99L));
            assertIae(() -> view.docLen(1L, -1));
            assertIae(() -> view.docLen(1L, 6));
            assertIae(() -> view.nTerms(1L, 6));
            Assert.assertEquals(1, view.segmentCount());
            Assert.assertEquals(64, view.fingerprint().length());
        }
    }

    /* -------- TR3.2 tf / idf / payload / hasTerm -------- */
    @Test(timeout = 60000)
    public void tr32_tfIdfPayloadHasTerm() throws Exception {
        long tfKey = TermDictStore.FarmHash64.mix4(100L, 1L, 1L, 0);
        build(10L, w -> {
            w.addTermStat(100L, 1.25, new byte[]{7, 8});
            w.addTf(tfKey, 5);
        });
        try (LayeredInvertedView view = LayeredInvertedView.open(store, new long[]{10L}); DsIndexSegmentReader r = store.openReader(10L)) {
            Assert.assertEquals("tf 与 P37 reader 全等", r.getTf(tfKey), view.tf(100L, 1L, 1L, 0));
            Assert.assertEquals(5, view.tf(100L, 1L, 1L, 0));
            Assert.assertEquals("tf miss=0", 0, view.tf(100L, 9L, 9L, 0));
            assertIae(() -> view.tf(100L, 1L, 1L, -1));
            assertIae(() -> view.tf(100L, 1L, 1L, 6));
            Assert.assertEquals(0, Double.compare(1.25, view.idf(100L)));
            Assert.assertEquals("idf miss=0.0", 0, Double.compare(0.0, view.idf(999L)));
            Assert.assertArrayEquals(new byte[]{7, 8}, view.termPayload(100L));
            Assert.assertNull("payload miss=null", view.termPayload(999L));
            Assert.assertTrue(view.hasTerm(100L));
            Assert.assertFalse(view.hasTerm(999L));
        }
    }

    /* -------- TR3.3 多段覆盖 + 归并等价 -------- */
    @Test(timeout = 60000)
    public void tr33_multiSegmentNewWinsAndCompactionEquivalence() throws Exception {
        long tfConflict = TermDictStore.FarmHash64.mix4(100L, 1L, 1L, 0);
        long tfExtra = TermDictStore.FarmHash64.mix4(300L, 3L, 3L, 0);
        build(10L, w -> {
            w.addDocStat(1L, six(11), six(1));
            w.addDocStat(2L, six(22), six(2));
            w.addTermStat(100L, 1.5, new byte[]{1});
            w.addTermStat(200L, 2.5, new byte[]{2});
            w.addTf(tfConflict, 5);
        });
        build(11L, w -> {
            w.addDocStat(1L, six(99), six(9)); // 与 seg10 冲突 → 新段优先
            w.addDocStat(3L, six(33), six(3));
            w.addTermStat(100L, 9.5, new byte[]{9}); // 冲突 → 新段优先
            w.addTermStat(300L, 3.5, new byte[]{3});
            w.addTf(tfConflict, 7); // 冲突 → 新段优先
            w.addTf(tfExtra, 8);
        });

        LayeredInvertedView newWins = LayeredInvertedView.open(store, new long[]{10L, 11L});
        LayeredInvertedView oldWins = LayeredInvertedView.open(store, new long[]{11L, 10L});
        Assert.assertEquals("新段优先 doc1", 99, newWins.docLen(1L, 0));
        Assert.assertEquals("反序 doc1 取旧段", 11, oldWins.docLen(1L, 0));
        Assert.assertEquals("新段优先 idf", 0, Double.compare(9.5, newWins.idf(100L)));
        Assert.assertEquals("新段优先 tf", 7, newWins.tf(100L, 1L, 1L, 0));
        Assert.assertEquals("反序 tf 取旧段", 5, oldWins.tf(100L, 1L, 1L, 0));
        Assert.assertNotEquals("段序变化 → 指纹变化", newWins.fingerprint(), oldWins.fingerprint());
        Assert.assertEquals(2, newWins.segmentCount());

        List<DsIndexSegmentReader> srcs = new ArrayList<>();
        srcs.add(store.openReader(10L));
        srcs.add(store.openReader(11L));
        DsIndexSegment merged;
        try {
            merged = new SegmentCompactor(store).compact(store.segmentFile(12L), srcs);
        } finally {
            for (DsIndexSegmentReader r : srcs) {
                r.close();
            }
        }
        store.append(12L, merged);
        try (LayeredInvertedView compacted = LayeredInvertedView.open(store, new long[]{12L})) {
            assertViewEquivalent(newWins, compacted);
        }

        newWins.close();
        newWins.close(); // 幂等
        oldWins.close();
    }

    /* -------- helpers -------- */
    private static void assertViewEquivalent(LayeredInvertedView a, LayeredInvertedView b) {
        for (long d : new long[]{1L, 2L, 3L, 4L}) {
            for (int lv = 0; lv < 6; lv++) {
                Assert.assertEquals("docLen doc" + d + " lv" + lv, a.docLen(d, lv), b.docLen(d, lv));
                Assert.assertEquals("nTerms doc" + d + " lv" + lv, a.nTerms(d, lv), b.nTerms(d, lv));
            }
            Assert.assertEquals("hasDoc " + d, a.hasDoc(d), b.hasDoc(d));
        }
        for (long u : new long[]{100L, 200L, 300L, 400L}) {
            Assert.assertEquals("idf " + u, 0, Double.compare(a.idf(u), b.idf(u)));
            byte[] pa = a.termPayload(u);
            byte[] pb = b.termPayload(u);
            if (pa == null) {
                Assert.assertNull("payload " + u, pb);
            } else {
                Assert.assertArrayEquals("payload " + u, pa, pb);
            }
            Assert.assertEquals("hasTerm " + u, a.hasTerm(u), b.hasTerm(u));
        }
        for (long uid : new long[]{100L, 200L, 300L}) {
            for (long doc : new long[]{1L, 2L, 3L}) {
                Assert.assertEquals("tf (" + uid + "," + doc + ")",
                        a.tf(uid, doc, doc, 0), b.tf(uid, doc, doc, 0));
            }
        }
    }

    private void build(long segId, Consumer<DsIndexSegmentWriter> fill) throws Exception {
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(store.segmentFile(segId));
        fill.accept(w);
        DsIndexSegment s = w.finish();
        store.append(segId, s);
    }

    private static int[] six(int v) {
        return new int[]{v, 0, 0, 0, 0, 0};
    }

    private static void assertIae(Runnable r) {
        try {
            r.run();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
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
