package ds;

import java.io.File;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P37 T3：DsIndexSegmentReader mmap 零重建，3 TR 0F0E。
 *
 * <p>
 * TR3.1 open + 三列二分查询（docStat/termStat/tf）+ payload 按需解压 + store.openReader 集成
 * <br>TR3.2 段损坏 IAE（null / 缺失 / 过短 / 坏 MAGIC / 坏 VERSION / CRC 不匹配）
 * <br>TR3.3 零重建自描述（空隔离目录 open）+ scanTfPrefix + 二次 open 全等 + close 幂等 + unmap 可删
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P37_T3_SegmentReaderTest {

    private File root;

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p37t3-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    /* -------- TR3.1 open + 二分查询 -------- */
    @Test
    public void tr31_openAndBinarySearch() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        File f = store.segmentFile(1L);
        DsIndexSegment s = writeSeg(f);
        store.append(1L, s);

        DsIndexSegmentReader r = store.openReader(1L);
        Assert.assertEquals(2L, r.docCount());
        Assert.assertEquals(2L, r.termCount());
        Assert.assertEquals(3L, r.tfCount());
        Assert.assertEquals(s.fingerprint(), r.segment().fingerprint());

        // docStat 命中 / miss
        FulltextStore.WeightDocStatRow d = r.getDocStat(20L);
        Assert.assertNotNull(d);
        Assert.assertArrayEquals(new int[]{7, 7, 7, 7, 7, 7}, d.docLen);
        Assert.assertArrayEquals(new int[]{1, 1, 1, 1, 1, 1}, d.nTerms);
        Assert.assertEquals(1, r.getDocStat(10L).docLen[0]);
        Assert.assertEquals(6, r.getDocStat(10L).nTerms[0]);
        Assert.assertNull(r.getDocStat(99L));

        // termStat 命中 + payload 按需解压
        FulltextStore.WeightTermStatRow t = r.getTermStat(100L);
        Assert.assertNotNull(t);
        Assert.assertEquals(2.5, t.idf, 0.0);
        Assert.assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, t.payload);
        Assert.assertNull(r.getTermStat(999L));

        // tf 命中 / miss=0
        Assert.assertEquals(3, r.getTf(1000L));
        Assert.assertEquals(7, r.getTf(2000L));
        Assert.assertEquals(0, r.getTf(2001L));

        r.close();
        store.close();
    }

    /* -------- TR3.2 段损坏 IAE -------- */
    @Test
    public void tr32_corruptIae() throws Exception {
        expectIae(() -> DsIndexSegmentReader.open(null));
        expectIae(() -> DsIndexSegmentReader.open(new File(root, "nope.didx")));

        File shortF = new File(root, "short.didx");
        Files.write(shortF.toPath(), new byte[100]);
        expectIae(() -> DsIndexSegmentReader.open(shortF));

        File f = new File(root, "a.didx");
        DsIndexSegment seg = writeSeg(f);
        byte[] ok = Files.readAllBytes(f.toPath());

        byte[] badMagic = ok.clone();
        badMagic[0] = 'X';
        File bm = new File(root, "bm.didx");
        Files.write(bm.toPath(), badMagic);
        expectIae(() -> DsIndexSegmentReader.open(bm));

        byte[] badVer = ok.clone();
        badVer[4] = 9;
        File bv = new File(root, "bv.didx");
        Files.write(bv.toPath(), badVer);
        expectIae(() -> DsIndexSegmentReader.open(bv));

        byte[] badCrc = ok.clone();
        badCrc[(int) seg.docStatOff + 20] ^= 0x1;
        File bc = new File(root, "bc.didx");
        Files.write(bc.toPath(), badCrc);
        expectIae(() -> DsIndexSegmentReader.open(bc));
    }

    /* -------- TR3.3 零重建 + 幂等 close -------- */
    @Test
    public void tr33_zeroRebuildIsolationAndIdempotentClose() throws Exception {
        File src = new File(root, "a.didx");
        writeSeg(src);
        // 复制到「无任何 P21/P22 源数据」的隔离目录：仅凭段文件即可读全量 → 零重建自描述
        File iso = new File(root, "iso");
        iso.mkdirs();
        File lone = new File(iso, "lone.didx");
        Files.copy(src.toPath(), lone.toPath());

        DsIndexSegmentReader r1 = DsIndexSegmentReader.open(lone);
        Assert.assertEquals(2L, r1.docCount());
        Assert.assertEquals(7, r1.getTf(2000L));
        Assert.assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, r1.getTermStat(100L).payload);

        long[] keys = r1.scanTfPrefix(0L, 0xFFFF_0000_0000_0000L);
        Assert.assertEquals(3, keys.length);
        Assert.assertArrayEquals(new long[]{1000L, 2000L, 3000L}, keys);
        // 全掩码 = 精确键匹配
        Assert.assertArrayEquals(new long[]{2000L}, r1.scanTfPrefix(2000L, -1L));

        r1.close();
        r1.close(); // 幂等
        expectIae(() -> r1.getTf(1000L));

        // 二次 open 全等（确定性）
        DsIndexSegmentReader r2 = DsIndexSegmentReader.open(lone);
        Assert.assertEquals(11, r2.getTf(3000L));
        Assert.assertEquals(r1.segment().fingerprint(), r2.segment().fingerprint());
        r2.close();

        // unmap 生效 → 段文件可立即删除（Windows 文件锁）
        Assert.assertTrue(lone.delete());
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

    private static DsIndexSegment writeSeg(File f) throws Exception {
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(f);
        w.addDocStat(10L, new int[]{1, 2, 3, 4, 5, 6}, new int[]{6, 5, 4, 3, 2, 1});
        w.addDocStat(20L, new int[]{7, 7, 7, 7, 7, 7}, new int[]{1, 1, 1, 1, 1, 1});
        w.addTermStat(100L, 2.5, new byte[]{1, 2, 3, 4, 5});
        w.addTermStat(200L, 0.5, new byte[]{9, 9});
        w.addTf(1000L, 3);
        w.addTf(2000L, 7);
        w.addTf(3000L, 11);
        return w.finish();
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
