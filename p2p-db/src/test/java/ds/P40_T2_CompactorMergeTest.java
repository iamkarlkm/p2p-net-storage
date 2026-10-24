package ds;

import java.io.File;
import java.io.RandomAccessFile;
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
import com.q3lives.ds.database.fulltext.compact.SegmentCompactor;
import com.q3lives.ds.database.fulltext.compact.SegmentMergeView;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P40 T2：SegmentCompactor 归并（新段优先），6 TR 0F0E。
 *
 * <p>
 * TR2.1 2 段归并 → docStat/termStat/tf 逐条 vs 手工真值（新段优先 + 旧段独有保留）
 * <br>TR2.2 产物 = 合法 P37 段（reader.open 头 + 4×CRC 全过）+ 合并前后 merge view 逐条等价
 * <br>TR2.3 单段归并恒等
 * <br>TR2.4 空段（0 记录）边界
 * <br>TR2.5 4 IAE（target null / sources null / sources 空 / sources 含 null）
 * <br>TR2.6 越界脏数据 IAE（tfCount 篡改）
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P40_T2_CompactorMergeTest {

    private File root;

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p40t2-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    /* -------- TR2.1 2 段归并真值 -------- */
    @Test
    public void tr21_twoWayMergeTruth() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        build(store, 10L, w -> {
            w.addDocStat(1L, six(1), six(1));
            w.addDocStat(2L, six(2), six(2));
            w.addTermStat(100L, 1.5, new byte[]{1});
            w.addTermStat(200L, 2.5, new byte[]{2});
            w.addTf(500L, 5);
            w.addTf(600L, 6);
        });
        build(store, 11L, w -> {
            w.addDocStat(1L, six(9), six(9));
            w.addDocStat(3L, six(3), six(3));
            w.addTermStat(100L, 9.25, new byte[]{9});
            w.addTermStat(300L, 3.5, new byte[]{3});
            w.addTf(500L, 9);
            w.addTf(700L, 7);
        });

        DsIndexSegment merged = new SegmentCompactor(store)
                .compact(store.segmentFile(12L), Arrays.asList(store.openReader(10L), store.openReader(11L)));
        store.append(12L, merged);

        try (DsIndexSegmentReader r = store.openReader(12L)) {
            Assert.assertEquals(3L, r.docCount());
            Assert.assertEquals(9, r.getDocStat(1L).docLen[0]);
            Assert.assertEquals(2, r.getDocStat(2L).docLen[0]);
            Assert.assertEquals(3, r.getDocStat(3L).docLen[0]);
            Assert.assertEquals(3L, r.termCount());
            Assert.assertEquals(0, Double.compare(9.25, r.getTermStat(100L).idf));
            Assert.assertArrayEquals(new byte[]{9}, r.getTermStat(100L).payload);
            Assert.assertEquals(0, Double.compare(2.5, r.getTermStat(200L).idf));
            Assert.assertEquals(0, Double.compare(3.5, r.getTermStat(300L).idf));
            Assert.assertEquals(3L, r.tfCount());
            Assert.assertEquals(9, r.getTf(500L));
            Assert.assertEquals(6, r.getTf(600L));
            Assert.assertEquals(7, r.getTf(700L));
        }
    }

    /* -------- TR2.2 产物合法性 + 等价性 -------- */
    @Test
    public void tr22_mergedIsValidSegmentAndEquivalent() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        build(store, 10L, w -> {
            w.addDocStat(1L, six(1), six(1));
            w.addTermStat(100L, 1.5, new byte[]{1});
            w.addTf(500L, 5);
        });
        build(store, 11L, w -> {
            w.addDocStat(2L, six(2), six(2));
            w.addTermStat(200L, 2.5, new byte[]{2});
            w.addTf(600L, 6);
        });

        long[] docs = {1L, 2L, 3L};
        long[] uids = {100L, 200L, 300L};
        long[] tfKeys = {500L, 600L, 700L};

        SegmentMergeView pre = new SegmentMergeView(Arrays.asList(store.openReader(10L), store.openReader(11L)));
        DsIndexSegment merged = new SegmentCompactor(store)
                .compact(store.segmentFile(12L), Arrays.asList(store.openReader(10L), store.openReader(11L)));
        store.append(12L, merged);

        try (SegmentMergeView post = new SegmentMergeView(Arrays.asList(store.openReader(12L)))) {
            for (long d : docs) {
                assertSameDoc(pre, post, d);
            }
            for (long u : uids) {
                assertSameTerm(pre, post, u);
            }
            for (long k : tfKeys) {
                Assert.assertEquals(pre.getTf(k), post.getTf(k));
            }
        }
        pre.close();
    }

    /* -------- TR2.3 单段归并恒等 -------- */
    @Test
    public void tr23_singleSourceIdentity() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        build(store, 10L, w -> {
            w.addDocStat(7L, six(4), six(4));
            w.addTermStat(100L, 1.5, new byte[]{1, 2});
            w.addTf(500L, 5);
        });
        DsIndexSegment merged = new SegmentCompactor(store)
                .compact(store.segmentFile(11L), Arrays.asList(store.openReader(10L)));
        store.append(11L, merged);

        try (DsIndexSegmentReader a = store.openReader(10L); DsIndexSegmentReader b = store.openReader(11L)) {
            Assert.assertEquals(a.docCount(), b.docCount());
            Assert.assertEquals(a.termCount(), b.termCount());
            Assert.assertEquals(a.tfCount(), b.tfCount());
            Assert.assertArrayEquals(a.getDocStat(7L).docLen, b.getDocStat(7L).docLen);
            Assert.assertEquals(0, Double.compare(a.getTermStat(100L).idf, b.getTermStat(100L).idf));
            Assert.assertArrayEquals(a.getTermStat(100L).payload, b.getTermStat(100L).payload);
            Assert.assertEquals(a.getTf(500L), b.getTf(500L));
        }
    }

    /* -------- TR2.4 空段边界 -------- */
    @Test
    public void tr24_emptySegmentBoundary() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        DsIndexSegment empty = build(store, 10L, w -> {
            // 0 记录段
        });
        Assert.assertEquals(0L, empty.docCount);
        Assert.assertEquals(0L, empty.termCount);
        Assert.assertEquals(0L, empty.tfCount);
        build(store, 11L, w -> {
            w.addDocStat(1L, six(1), six(1));
            w.addTf(500L, 5);
        });

        DsIndexSegment merged = new SegmentCompactor(store)
                .compact(store.segmentFile(12L), Arrays.asList(store.openReader(10L), store.openReader(11L)));
        store.append(12L, merged);

        try (DsIndexSegmentReader r = store.openReader(12L)) {
            Assert.assertEquals(1L, r.docCount());
            Assert.assertEquals(5, r.getTf(500L));
        }
    }

    /* -------- TR2.5 4 IAE -------- */
    @Test
    public void tr25_guards() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        build(store, 10L, w -> w.addDocStat(1L, six(1), six(1)));
        SegmentCompactor compactor = new SegmentCompactor(store);
        File target = store.segmentFile(11L);
        DsIndexSegmentReader r10 = store.openReader(10L);

        expectIae(() -> new SegmentCompactor(null));
        expectIae(() -> compactor.compact(null, Arrays.asList(r10)));
        expectIae(() -> compactor.compact(target, null));
        expectIae(() -> compactor.compact(target, new ArrayList<>()));

        List<DsIndexSegmentReader> withNull = new ArrayList<>();
        withNull.add(r10);
        withNull.add(null);
        expectIae(() -> compactor.compact(target, withNull));
        r10.close();
    }

    /* -------- TR2.6 脏数据 IAE -------- */
    @Test
    public void tr26_dirtyCountRejected() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        build(store, 10L, w -> w.addTf(500L, 5));

        File f = store.segmentFile(10L);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.seek(24L);
            raf.writeLong(1_000_000L);
        }
        DsIndexSegmentReader r10 = store.openReader(10L);
        Assert.assertEquals(1_000_000L, r10.segment().tfCount);
        expectIae(() -> new SegmentCompactor(store).compact(store.segmentFile(11L), Arrays.asList(r10)));
        r10.close();
    }

    /* -------- helpers -------- */
    private static void assertSameDoc(SegmentMergeView a, SegmentMergeView b, long docId) {
        FulltextStore.WeightDocStatRow ra = a.getDocStat(docId);
        FulltextStore.WeightDocStatRow rb = b.getDocStat(docId);
        if (ra == null) {
            Assert.assertNull(rb);
            return;
        }
        Assert.assertNotNull(rb);
        Assert.assertArrayEquals(ra.docLen, rb.docLen);
        Assert.assertArrayEquals(ra.nTerms, rb.nTerms);
    }

    private static void assertSameTerm(SegmentMergeView a, SegmentMergeView b, long uid) {
        FulltextStore.WeightTermStatRow ra = a.getTermStat(uid);
        FulltextStore.WeightTermStatRow rb = b.getTermStat(uid);
        if (ra == null) {
            Assert.assertNull(rb);
            return;
        }
        Assert.assertNotNull(rb);
        Assert.assertEquals(0, Double.compare(ra.idf, rb.idf));
        Assert.assertArrayEquals(ra.payload, rb.payload);
    }

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

    private static DsIndexSegment build(DsIndexSegmentStore store, long segId, Consumer<DsIndexSegmentWriter> fill)
            throws Exception {
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(store.segmentFile(segId));
        fill.accept(w);
        DsIndexSegment s = w.finish();
        store.append(segId, s);
        return s;
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
