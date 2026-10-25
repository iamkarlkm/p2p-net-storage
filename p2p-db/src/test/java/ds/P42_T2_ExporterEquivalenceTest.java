package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.layered.LayeredIndexExporter;
import com.q3lives.ds.database.fulltext.layered.LayeredSnapshot;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;

/**
 * P42 T2：{@link LayeredIndexExporter} 导出等价性，6 TR 0F0E。
 *
 * <p>
 * 小语料 2 文档 {@code ingestDoc} 后导出 → 段三列逐条与内存 WeightStore 真值全等； 另含空 store 边界与 P37
 * 段格式合法性（R5）。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P42_T2_ExporterEquivalenceTest {

    private File root;
    private FulltextStore ft;
    private DsIndexSegmentStore store;

    private static final long SEG = 10L;

    @Before
    public void setUp() throws Exception {
        root = new File(System.getProperty("java.io.tmpdir"), "p42t2-" + System.nanoTime());
        root.mkdirs();
        ft = new FulltextStore(root.getAbsolutePath());
        store = new DsIndexSegmentStore(root.getAbsolutePath());
        ft.ingestDoc(1L, "hello world trae");
        ft.ingestDoc(2L, "hello trae engine");
        // P21 termStat（idf）为惰性：ingestDoc 不落盘，需显式物化后才可导出等价校验。
        List<Long> uids = new ArrayList<>();
        for (String t : new String[]{"hello", "world", "trae", "engine"}) {
            long u = ft.getTermDict().toUid(t, false);
            if (u > 0L) {
                uids.add(u);
            }
        }
        ft.recomputeIdfForTerms(uids);
    }

    @After
    public void tearDown() {
        if (ft != null) {
            ft.close();
        }
        deleteRecursive(root);
    }

    /* -------- TR2.1 docStat 计数与逐条等价 -------- */
    @Test(timeout = 60000)
    public void tr21_docCountAndDocStatCellEquivalence() throws Exception {
        LayeredSnapshot snap = new LayeredIndexExporter().export(ft, store, SEG);
        Set<Long> docKeys = ft.weightStoreAllDocKeysForTest();
        Assert.assertEquals("docCount == docStat 键集 size", docKeys.size(), snap.docCount);
        try (DsIndexSegmentReader r = store.openReader(SEG)) {
            for (Long docId : docKeys) {
                FulltextStore.WeightDocStatRow truth = ft.getDocStatForTest(docId);
                FulltextStore.WeightDocStatRow got = r.getDocStat(docId);
                Assert.assertNotNull("doc " + docId + " 应命中", got);
                Assert.assertArrayEquals("doc " + docId + " docLen", truth.docLen, got.docLen);
                Assert.assertArrayEquals("doc " + docId + " nTerms", truth.nTerms, got.nTerms);
            }
        }
    }

    /* -------- TR2.2 termStat 计数与逐条等价 -------- */
    @Test(timeout = 60000)
    public void tr22_termCountAndTermStatCellEquivalence() throws Exception {
        LayeredSnapshot snap = new LayeredIndexExporter().export(ft, store, SEG);
        Set<Long> termKeys = ft.weightStoreAllTermKeys();
        Assert.assertEquals("termCount == termStat 键集 size", termKeys.size(), snap.termCount);
        Assert.assertTrue("语料应产出若干 term", termKeys.size() > 0);
        try (DsIndexSegmentReader r = store.openReader(SEG)) {
            for (Long uid : termKeys) {
                FulltextStore.WeightTermStatRow truth = ft.getTermStatForTest(uid);
                FulltextStore.WeightTermStatRow got = r.getTermStat(uid);
                Assert.assertNotNull("uid " + uid + " 应命中", got);
                Assert.assertEquals("uid " + uid + " idf bit 级等价", 0,
                        Double.compare(truth.idf, got.idf));
                Assert.assertArrayEquals("uid " + uid + " payload", truth.payload, got.payload);
            }
        }
    }

    /* -------- TR2.3 tf 计数与逐条等价 -------- */
    @Test(timeout = 60000)
    public void tr23_tfCountAndTfCellEquivalence() throws Exception {
        LayeredSnapshot snap = new LayeredIndexExporter().export(ft, store, SEG);
        Map<Long, Integer> tfEntries = ft.weightStoreAllTfEntries();
        Assert.assertEquals("tfCount == tf 键值映射 size", tfEntries.size(), snap.tfCount);
        Assert.assertTrue("语料应产出若干 tf", tfEntries.size() > 0);
        try (DsIndexSegmentReader r = store.openReader(SEG)) {
            for (Map.Entry<Long, Integer> e : tfEntries.entrySet()) {
                Assert.assertEquals("tf key=" + e.getKey(), (int) e.getValue(), r.getTf(e.getKey()));
            }
        }
    }

    /* -------- TR2.4 空 store 边界 -------- */
    @Test(timeout = 60000)
    public void tr24_emptyStoreExportsLegalSegment() throws Exception {
        DsIndexSegmentStore emptyStore = new DsIndexSegmentStore(new File(root, "empty").getAbsolutePath());
        FulltextStore emptyFt = new FulltextStore(new File(root, "empty").getAbsolutePath());
        try {
            LayeredSnapshot snap = new LayeredIndexExporter().export(emptyFt, emptyStore, SEG);
            Assert.assertEquals(0L, snap.docCount);
            Assert.assertEquals(0L, snap.termCount);
            Assert.assertEquals(0L, snap.tfCount);
            try (DsIndexSegmentReader r = emptyStore.openReader(SEG)) {
                Assert.assertEquals(0L, r.docCount());
                Assert.assertEquals(0L, r.termCount());
                Assert.assertEquals(0L, r.tfCount());
                Assert.assertNull("空段 docStat 必 miss", r.getDocStat(1L));
                Assert.assertEquals("空段 tf 必 miss=0", 0, r.getTf(12345L));
            }
        } finally {
            emptyFt.close();
        }
    }

    /* -------- TR2.5 P37 段格式合法（R5） -------- */
    @Test(timeout = 60000)
    public void tr25_exportedSegmentIsLegalP37Segment() throws Exception {
        LayeredSnapshot snap = new LayeredIndexExporter().export(ft, store, SEG);
        Assert.assertEquals(64, snap.segmentFingerprint.length());
        DsIndexSegment onDisk = store.load(SEG);
        Assert.assertEquals("段格式 0 变更：MAGIC=DIDX", "DIDX",
                new String(DsIndexSegment.MAGIC, java.nio.charset.StandardCharsets.US_ASCII));
        Assert.assertEquals("version", DsIndexSegment.VERSION, onDisk.version);
        Assert.assertEquals("segmentFingerprint == 磁盘段指纹", onDisk.fingerprint(), snap.segmentFingerprint);
        Assert.assertEquals(DsIndexSegment.CRC_SECTIONS, onDisk.crc32.length);
        Assert.assertEquals(SEG, snap.segId);
        // 磁盘段文件长度 ≥ HEADER_SIZE 且各 section 偏移 4KB 对齐
        long len = store.segmentFile(SEG).length();
        Assert.assertTrue("段文件长度应 ≥ HEADER_SIZE", len >= DsIndexSegment.HEADER_SIZE);
        Assert.assertEquals("docStatOff 应 4KB 对齐", 0L, onDisk.docStatOff % DsIndexSegment.HEADER_SIZE);
        Assert.assertEquals("varOff 应 4KB 对齐", 0L, onDisk.varOff % DsIndexSegment.HEADER_SIZE);
    }

    /* -------- TR2.6 4 IAE 守卫 -------- */
    @Test(timeout = 60000)
    public void tr26_exporterIaeGuards() throws Exception {
        LayeredIndexExporter ex = new LayeredIndexExporter();
        assertIae(() -> ex.export(null, store, SEG));
        assertIae(() -> ex.export(ft, null, SEG));
        assertIae(() -> ex.export(ft, store, -1L));
        ex.export(ft, store, SEG);
        assertIae(() -> ex.export(ft, store, SEG));
    }

    /* -------- helpers -------- */
    private interface ExportCall {

        void run() throws Exception;
    }

    private static void assertIae(ExportCall c) throws Exception {
        try {
            c.run();
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
