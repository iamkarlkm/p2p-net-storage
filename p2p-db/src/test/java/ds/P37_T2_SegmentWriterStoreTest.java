package ds;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;
import com.q3lives.ds.database.fulltext.segment.SegmentColumnCodec;

/**
 * P37 T2：DsIndexSegmentWriter + DsIndexSegmentStore，6 TR 0F0E。
 *
 * <p>
 * TR2.1 writer 三列排序 + 同键覆盖 + 4KB 对齐 + 定长记录
 * <br>TR2.2 动态区逐页 LZ4 + 四段 CRC32 一致
 * <br>TR2.3 writer 6 IAE + finish 幂等
 * <br>TR2.4 原子 rename（无 .tmp 残留 / 文件尾部无冗余）
 * <br>TR2.5 store append → list → load round-trip
 * <br>TR2.6 store remove + 5 IAE（rootDir 空 / segId&lt;0 / 文件缺失 / 重复 append / 段不存在 / manifest 损坏）
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P37_T2_SegmentWriterStoreTest {

    private File root;

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p37t2-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    /* -------- TR2.1 排序 + 覆盖 + 对齐 -------- */

    @Test
    public void tr21_writerSortDedupLayout() throws Exception {
        File seg = new File(root, "a.didx");
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(seg);
        // 乱序喂入 + 重复 docId（后者覆盖）
        w.addDocStat(30L, new int[]{3, 0, 0, 0, 0, 0}, new int[]{1, 0, 0, 0, 0, 0});
        w.addDocStat(10L, new int[]{1, 0, 0, 0, 0, 0}, new int[]{1, 0, 0, 0, 0, 0});
        w.addDocStat(30L, new int[]{9, 0, 0, 0, 0, 0}, new int[]{2, 0, 0, 0, 0, 0});
        w.addDocStat(20L, new int[]{2, 0, 0, 0, 0, 0}, new int[]{1, 0, 0, 0, 0, 0});
        w.addTermStat(200L, 2.0, new byte[]{2});
        w.addTermStat(100L, 1.0, new byte[]{1});
        w.addTermStat(100L, 1.5, new byte[]{1, 1});
        w.addTf(500L, 5);
        w.addTf(300L, 3);
        w.addTf(500L, 9);
        DsIndexSegment s = w.finish();

        Assert.assertEquals(3L, s.docCount);
        Assert.assertEquals(2L, s.termCount);
        Assert.assertEquals(2L, s.tfCount);
        // 4KB 页对齐（R9）
        Assert.assertEquals(DsIndexSegment.HEADER_SIZE, s.docStatOff);
        Assert.assertEquals(0L, s.docStatOff % 4096);
        Assert.assertEquals(0L, s.termStatOff % 4096);
        Assert.assertEquals(0L, s.tfOff % 4096);
        Assert.assertEquals(0L, s.varOff % 4096);
        Assert.assertEquals(s.docCount * SegmentColumnCodec.DOC_STAT_REC, s.docStatLen);
        Assert.assertEquals(s.termCount * SegmentColumnCodec.TERM_STAT_REC, s.termStatLen);
        Assert.assertEquals(s.tfCount * SegmentColumnCodec.TF_REC, s.tfLen);

        // 头可回读且指纹一致
        DsIndexSegment parsed = SegmentColumnCodec.readHeader(ByteBuffer.wrap(readBytes(seg, 0, DsIndexSegment.HEADER_SIZE)));
        Assert.assertEquals(s.fingerprint(), parsed.fingerprint());

        // docStat 列按 docId 升序 + 覆盖生效
        ByteBuffer docCol = readSection(seg, parsed.docStatOff, parsed.docStatLen);
        int rec = SegmentColumnCodec.DOC_STAT_REC;
        Assert.assertEquals(10L, docCol.getLong(0));
        Assert.assertEquals(20L, docCol.getLong(rec));
        Assert.assertEquals(30L, docCol.getLong(2 * rec));
        Assert.assertEquals(1, docCol.getInt(8));
        Assert.assertEquals(9, docCol.getInt(2 * rec + 8));

        // termStat / tf 列升序
        ByteBuffer termCol = readSection(seg, parsed.termStatOff, parsed.termStatLen);
        Assert.assertEquals(100L, termCol.getLong(0));
        Assert.assertEquals(200L, termCol.getLong(SegmentColumnCodec.TERM_STAT_REC));
        Assert.assertEquals(1.5, termCol.getDouble(8), 0.0);
        ByteBuffer tfCol = readSection(seg, parsed.tfOff, parsed.tfLen);
        Assert.assertEquals(300L, tfCol.getLong(0));
        Assert.assertEquals(500L, tfCol.getLong(SegmentColumnCodec.TF_REC));
        Assert.assertEquals(9, tfCol.getInt(SegmentColumnCodec.TF_REC + 8));
    }

    /* -------- TR2.2 动态区 LZ4 + CRC -------- */

    @Test
    public void tr22_dynamicLz4AndCrc() throws Exception {
        File seg = new File(root, "b.didx");
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(seg);
        byte[] big = new byte[5000];
        Arrays.fill(big, (byte) 'z');
        w.addTermStat(1L, 1.0, big);
        w.addTermStat(2L, 2.0, new byte[]{7, 8, 9});
        w.addDocStat(5L, new int[6], new int[6]);
        w.addTf(1L, 1);
        DsIndexSegment s = w.finish();

        // 四段 CRC32 可复算一致
        Assert.assertEquals(s.crc32[0],
                SegmentColumnCodec.crc32(readBytes(seg, (int) s.docStatOff, (int) s.docStatLen)));
        Assert.assertEquals(s.crc32[1],
                SegmentColumnCodec.crc32(readBytes(seg, (int) s.termStatOff, (int) s.termStatLen)));
        Assert.assertEquals(s.crc32[2], SegmentColumnCodec.crc32(readBytes(seg, (int) s.tfOff, (int) s.tfLen)));
        byte[] var = readBytes(seg, (int) s.varOff, (int) s.varLen);
        Assert.assertEquals(s.crc32[3], SegmentColumnCodec.crc32(var));

        // 压缩确有收益（5000B 周期串 → LZ4）
        Assert.assertTrue("var section should be compressed", s.varLen < big.length);

        // 按需解压 payload 全等
        ByteBuffer termCol = readSection(seg, s.termStatOff, s.termStatLen);
        Assert.assertEquals(1L, termCol.getLong(0));
        int po = termCol.getInt(16);
        int pl = termCol.getInt(20);
        Assert.assertArrayEquals(big, SegmentColumnCodec.readVar(ByteBuffer.wrap(var), po, pl));
    }

    /* -------- TR2.3 writer 6 IAE + finish 幂等 -------- */

    @Test
    public void tr23_writerIaeAndIdempotent() throws Exception {
        expectIae(() -> new DsIndexSegmentWriter(null));
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(new File(root, "c.didx"));
        expectIae(() -> w.addDocStat(-1L, new int[6], new int[6]));
        expectIae(() -> w.addDocStat(1L, new int[5], new int[6]));
        expectIae(() -> w.addDocStat(1L, new int[6], new int[5]));
        expectIae(() -> w.addTf(1L, -1));
        expectIae(() -> w.addTermStat(1L, Double.NaN, new byte[0]));
        expectIae(() -> w.addTermStat(1L, Double.POSITIVE_INFINITY, new byte[0]));
        w.finish();
        expectIae(w::finish);
    }

    /* -------- TR2.4 原子 rename -------- */

    @Test
    public void tr24_atomicRenameNoTmpLeft() throws Exception {
        File seg = new File(root, "d.didx");
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(seg);
        w.addDocStat(1L, new int[6], new int[6]);
        w.finish();
        Assert.assertTrue(seg.isFile());
        Assert.assertFalse(new File(seg.getAbsolutePath() + ".tmp").exists());
        DsIndexSegment s = SegmentColumnCodec.readHeader(ByteBuffer.wrap(readBytes(seg, 0, DsIndexSegment.HEADER_SIZE)));
        Assert.assertEquals(s.varOff + s.varLen, seg.length());
    }

    /* -------- TR2.5 store append → list → load -------- */

    @Test
    public void tr25_storeAppendListLoad() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        Assert.assertEquals(0, store.list().length);
        File f = store.segmentFile(7L);
        Assert.assertEquals("seg-7.didx", f.getName());
        Assert.assertTrue(f.getParentFile().isDirectory());

        DsIndexSegmentWriter w = new DsIndexSegmentWriter(f);
        w.addDocStat(1L, new int[]{1, 0, 0, 0, 0, 0}, new int[]{1, 0, 0, 0, 0, 0});
        w.addTermStat(9L, 1.25, new byte[]{1, 2, 3});
        w.addTf(100L, 4);
        DsIndexSegment s = w.finish();

        DsIndexSegment reg = store.append(7L, s);
        Assert.assertEquals(s.fingerprint(), reg.fingerprint());
        Assert.assertArrayEquals(new long[]{7L}, store.list());
        Assert.assertEquals(s.fingerprint(), store.load(7L).fingerprint());
        store.close();
    }

    /* -------- TR2.6 store remove + 5 IAE -------- */

    @Test
    public void tr26_storeRemoveAndIae() throws Exception {
        expectIae(() -> new DsIndexSegmentStore(null));
        expectIae(() -> new DsIndexSegmentStore("   "));

        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        expectIae(() -> store.segmentFile(-1L));
        expectIae(() -> store.append(3L, sampleSeg()));

        File f = store.segmentFile(3L);
        DsIndexSegment s = writeSample(f);
        store.append(3L, s);
        expectIae(() -> store.append(3L, s));
        expectIae(() -> store.load(999L));

        store.remove(3L);
        Assert.assertEquals(0, store.list().length);
        Assert.assertFalse(f.exists());
        expectIae(() -> store.remove(3L));

        Files.write(new File(store.dir(), "manifest").toPath(), "notanumber\n".getBytes(StandardCharsets.UTF_8));
        expectIae(store::list);
        store.close();
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

    private static DsIndexSegment writeSample(File f) throws Exception {
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(f);
        w.addDocStat(1L, new int[]{1, 0, 0, 0, 0, 0}, new int[]{1, 0, 0, 0, 0, 0});
        return w.finish();
    }

    private static DsIndexSegment sampleSeg() {
        return new DsIndexSegment(DsIndexSegment.VERSION, 0L, 0L, 0L,
                4096L, 0L, 4096L, 0L, 4096L, 0L, 4096L, 0L, new int[]{0, 0, 0, 0});
    }

    private static byte[] readBytes(File f, int off, int len) throws Exception {
        byte[] all = Files.readAllBytes(f.toPath());
        return Arrays.copyOfRange(all, off, off + len);
    }

    private static ByteBuffer readSection(File f, long off, long len) throws Exception {
        return ByteBuffer.wrap(readBytes(f, (int) off, (int) len));
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
