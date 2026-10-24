package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.compact.SegmentMergeView;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P40 T1：SegmentMergeView 骨架 + 覆盖语义，5 TR 0F0E。
 *
 * <p>
 * TR1.1 3 IAE（readers null / 空 / 含 null）+ segmentCount
 * <br>TR1.2 docStat 覆盖：新段优先（同 docId 取新段值）+ 旧段独有可见 + 全 miss = null
 * <br>TR1.3 termStat 覆盖：新段优先（同 uid 取新段 idf bit 级 + payload）+ 全 miss = null
 * <br>TR1.4 tf 覆盖：新段优先 + 旧段独有可见 + 全 miss = 0
 * <br>TR1.5 fingerprint 64-hex 稳定且与段序强相关 + close 幂等
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P40_T1_MergeViewSkeletonTest {

    private File root;

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p40t1-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    /* -------- TR1.1 3 IAE + segmentCount -------- */
    @Test
    public void tr11_guardsAndSegmentCount() throws Exception {
        expectIae(() -> new SegmentMergeView(null));
        expectIae(() -> new SegmentMergeView(new ArrayList<>()));

        List<DsIndexSegmentReader> withNull = new ArrayList<>();
        withNull.add(null);
        expectIae(() -> new SegmentMergeView(withNull));

        File a = seg("a.didx", w -> w.addDocStat(1L, ones(), ones()));
        File b = seg("b.didx", w -> w.addDocStat(2L, ones(), ones()));
        try (SegmentMergeView view
                = new SegmentMergeView(Arrays.asList(DsIndexSegmentReader.open(a), DsIndexSegmentReader.open(b)))) {
            Assert.assertEquals(2, view.segmentCount());
        }
    }

    /* -------- TR1.2 docStat 覆盖 -------- */
    @Test
    public void tr12_docStatNewWins() throws Exception {
        File oldSeg = seg("old.didx", w -> {
            w.addDocStat(1L, new int[]{1, 0, 0, 0, 0, 0}, new int[]{1, 0, 0, 0, 0, 0});
            w.addDocStat(2L, new int[]{2, 0, 0, 0, 0, 0}, new int[]{2, 0, 0, 0, 0, 0});
        });
        File newSeg = seg("new.didx", w -> {
            w.addDocStat(1L, new int[]{9, 0, 0, 0, 0, 0}, new int[]{9, 0, 0, 0, 0, 0});
            w.addDocStat(3L, new int[]{3, 0, 0, 0, 0, 0}, new int[]{3, 0, 0, 0, 0, 0});
        });
        try (SegmentMergeView view = new SegmentMergeView(
                Arrays.asList(DsIndexSegmentReader.open(oldSeg), DsIndexSegmentReader.open(newSeg)))) {
            Assert.assertEquals(9, view.getDocStat(1L).docLen[0]);
            Assert.assertEquals(9, view.getDocStat(1L).nTerms[0]);
            Assert.assertEquals(2, view.getDocStat(2L).docLen[0]);
            Assert.assertEquals(3, view.getDocStat(3L).docLen[0]);
            Assert.assertNull(view.getDocStat(99L));
        }
    }

    /* -------- TR1.3 termStat 覆盖 -------- */
    @Test
    public void tr13_termStatNewWins() throws Exception {
        File oldSeg = seg("old.didx", w -> {
            w.addTermStat(100L, 1.5, new byte[]{1, 2});
            w.addTermStat(200L, 2.5, new byte[]{7});
        });
        File newSeg = seg("new.didx", w -> w.addTermStat(100L, 9.25, new byte[]{9, 8, 7}));
        try (SegmentMergeView view = new SegmentMergeView(
                Arrays.asList(DsIndexSegmentReader.open(oldSeg), DsIndexSegmentReader.open(newSeg)))) {
            FulltextStore.WeightTermStatRow r100 = view.getTermStat(100L);
            Assert.assertEquals(0, Double.compare(9.25, r100.idf));
            Assert.assertArrayEquals(new byte[]{9, 8, 7}, r100.payload);
            Assert.assertEquals(0, Double.compare(2.5, view.getTermStat(200L).idf));
            Assert.assertArrayEquals(new byte[]{7}, view.getTermStat(200L).payload);
            Assert.assertNull(view.getTermStat(999L));
        }
    }

    /* -------- TR1.4 tf 覆盖 -------- */
    @Test
    public void tr14_tfNewWins() throws Exception {
        File oldSeg = seg("old.didx", w -> {
            w.addTf(500L, 5);
            w.addTf(600L, 6);
        });
        File newSeg = seg("new.didx", w -> w.addTf(500L, 9));
        try (SegmentMergeView view = new SegmentMergeView(
                Arrays.asList(DsIndexSegmentReader.open(oldSeg), DsIndexSegmentReader.open(newSeg)))) {
            Assert.assertEquals(9, view.getTf(500L));
            Assert.assertEquals(6, view.getTf(600L));
            Assert.assertEquals(0, view.getTf(700L));
        }
    }

    /* -------- TR1.5 fingerprint + close -------- */
    @Test
    public void tr15_fingerprintAndCloseIdempotent() throws Exception {
        File oldSeg = seg("old.didx", w -> w.addDocStat(1L, ones(), ones()));
        File newSeg = seg("new.didx", w -> w.addDocStat(2L, ones(), ones()));

        String fp;
        SegmentMergeView view = new SegmentMergeView(
                Arrays.asList(DsIndexSegmentReader.open(oldSeg), DsIndexSegmentReader.open(newSeg)));
        fp = view.fingerprint();
        Assert.assertEquals(64, fp.length());
        Assert.assertTrue(fp.matches("[0-9a-f]{64}"));
        view.close();
        view.close();

        String fpAgain = new SegmentMergeView(
                Arrays.asList(DsIndexSegmentReader.open(oldSeg), DsIndexSegmentReader.open(newSeg))).fingerprint();
        Assert.assertEquals(fp, fpAgain);

        String fpReversed = new SegmentMergeView(
                Arrays.asList(DsIndexSegmentReader.open(newSeg), DsIndexSegmentReader.open(oldSeg))).fingerprint();
        Assert.assertNotEquals(fp, fpReversed);
    }

    /* -------- helpers -------- */
    private interface ThrowingRunnable {

        void run() throws Exception;
    }

    private static void expectIae(ThrowingRunnable r) throws Exception {
        try {
            r.run();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private File seg(String name, Consumer<DsIndexSegmentWriter> fill) throws Exception {
        File f = new File(root, name);
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(f);
        fill.accept(w);
        w.finish();
        return f;
    }

    private static int[] ones() {
        return new int[]{1, 0, 0, 0, 0, 0};
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
