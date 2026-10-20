package ds;

import java.nio.ByteBuffer;

import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.SegmentColumnCodec;

/**
 * P37 T1：DsIndexSegment + SegmentColumnCodec skeleton，5 TR 0F0E。
 *
 * <p>
 * TR1.1 DsIndexSegment 不可变 POJO 5 IAE
 * <br>TR1.2 DsIndexSegment fingerprint SHA-256 64-hex 跨 run 一致
 * <br>TR1.3 packDocStat 定长列 round-trip + 二分命中/miss
 * <br>TR1.4 packTermStat / packTf round-trip + 二分
 * <br>TR1.5 packDynamic / readVar 压缩 round-trip + codec 5 IAE
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P37_T1_SegmentSkeletonTest {

    private static final int[] CRCS = {0, 0, 0, 0};

    /* -------- TR1.1 DsIndexSegment 5 IAE -------- */
    @Test
    public void tr11_segment5Iae() {
        DsIndexSegment s = new DsIndexSegment(DsIndexSegment.VERSION, 1L, 0L, 0L,
                4096L, SegmentColumnCodec.DOC_STAT_REC, 0L, 0L, 0L, 0L, 0L, 0L, CRCS);
        Assert.assertEquals(DsIndexSegment.VERSION, s.version);
        Assert.assertEquals(1L, s.docCount);

        // IAE1 version mismatch
        try {
            new DsIndexSegment(2, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, CRCS);
            Assert.fail("version IAE");
        } catch (IllegalArgumentException ignore) {}
        // IAE2 count negative
        try {
            new DsIndexSegment(DsIndexSegment.VERSION, -1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, CRCS);
            Assert.fail("count IAE");
        } catch (IllegalArgumentException ignore) {}
        // IAE3 off/len negative
        try {
            new DsIndexSegment(DsIndexSegment.VERSION, 0L, 0L, 0L, -1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, CRCS);
            Assert.fail("off IAE");
        } catch (IllegalArgumentException ignore) {}
        // IAE4 crc32 length
        try {
            new DsIndexSegment(DsIndexSegment.VERSION, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, new int[]{0});
            Assert.fail("crc IAE");
        } catch (IllegalArgumentException ignore) {}
        // IAE5 docStatLen mismatch
        try {
            new DsIndexSegment(DsIndexSegment.VERSION, 3L, 0L, 0L, 4096L, 10L, 0L, 0L, 0L, 0L, 0L, 0L, CRCS);
            Assert.fail("docStatLen IAE");
        } catch (IllegalArgumentException ignore) {}
    }

    /* -------- TR1.2 fingerprint -------- */
    @Test
    public void tr12_fingerprint() {
        DsIndexSegment a = new DsIndexSegment(DsIndexSegment.VERSION, 2L, 3L, 4L,
                4096L, 2 * SegmentColumnCodec.DOC_STAT_REC, 8192L, 0L, 8192L, 0L, 8192L, 0L, new int[]{11, 22, 33, 44});
        DsIndexSegment b = new DsIndexSegment(DsIndexSegment.VERSION, 2L, 3L, 4L,
                4096L, 2 * SegmentColumnCodec.DOC_STAT_REC, 8192L, 0L, 8192L, 0L, 8192L, 0L, new int[]{11, 22, 33, 44});
        String fa = a.fingerprint();
        Assert.assertEquals(64, fa.length());
        Assert.assertTrue("hex", fa.matches("[0-9a-f]{64}"));
        Assert.assertEquals("跨 run 一致", fa, b.fingerprint());
        DsIndexSegment c = new DsIndexSegment(DsIndexSegment.VERSION, 2L, 3L, 4L,
                4096L, 2 * SegmentColumnCodec.DOC_STAT_REC, 8192L, 0L, 8192L, 0L, 8192L, 999L, new int[]{11, 22, 33, 44});
        Assert.assertNotEquals("字段差异 → 指纹差异", fa, c.fingerprint());
    }

    /* -------- TR1.3 packDocStat -------- */
    @Test
    public void tr13_packDocStat() {
        long[] ids = {5L, 9L, 42L};
        int[][] dl = {{1, 2, 3, 4, 5, 6}, {7, 8, 9, 10, 11, 12}, {0, 0, 0, 0, 0, 0}};
        int[][] nt = {{1, 0, 0, 0, 0, 0}, {2, 2, 0, 0, 0, 0}, {3, 3, 3, 0, 0, 0}};
        byte[] col = SegmentColumnCodec.packDocStat(ids, dl, nt);
        Assert.assertEquals(3 * SegmentColumnCodec.DOC_STAT_REC, col.length);
        Assert.assertEquals(56, SegmentColumnCodec.DOC_STAT_REC);
        ByteBuffer bb = ByteBuffer.wrap(col);
        for (int i = 0; i < 3; i++) {
            Assert.assertEquals(ids[i], bb.getLong(i * SegmentColumnCodec.DOC_STAT_REC));
            int base = i * SegmentColumnCodec.DOC_STAT_REC + 8;
            for (int k = 0; k < 6; k++) {
                Assert.assertEquals(dl[i][k], bb.getInt(base + k * 4));
            }
            for (int k = 0; k < 6; k++) {
                Assert.assertEquals(nt[i][k], bb.getInt(base + 24 + k * 4));
            }
        }
        // 二分命中 / miss
        Assert.assertEquals(0, SegmentColumnCodec.binarySearchKey(bb, SegmentColumnCodec.DOC_STAT_REC, 3, 5L));
        Assert.assertEquals(2, SegmentColumnCodec.binarySearchKey(bb, SegmentColumnCodec.DOC_STAT_REC, 3, 42L));
        Assert.assertEquals(-1, SegmentColumnCodec.binarySearchKey(bb, SegmentColumnCodec.DOC_STAT_REC, 3, 6L));
    }

    /* -------- TR1.4 packTermStat / packTf -------- */
    @Test
    public void tr14_packTermStatAndTf() {
        long[] uids = {100L, 200L, 300L};
        double[] idf = {1.5, 2.25, 0.75};
        int[] po = {0, 4, 8};
        int[] pl = {4, 4, 4};
        byte[] ts = SegmentColumnCodec.packTermStat(uids, idf, po, pl);
        Assert.assertEquals(3 * SegmentColumnCodec.TERM_STAT_REC, ts.length);
        ByteBuffer tb = ByteBuffer.wrap(ts);
        for (int i = 0; i < 3; i++) {
            int b = i * SegmentColumnCodec.TERM_STAT_REC;
            Assert.assertEquals(uids[i], tb.getLong(b));
            Assert.assertEquals(idf[i], tb.getDouble(b + 8), 0.0);
            Assert.assertEquals(po[i], tb.getInt(b + 16));
            Assert.assertEquals(pl[i], tb.getInt(b + 20));
        }
        Assert.assertEquals(1, SegmentColumnCodec.binarySearchKey(tb, SegmentColumnCodec.TERM_STAT_REC, 3, 200L));
        Assert.assertEquals(-1, SegmentColumnCodec.binarySearchKey(tb, SegmentColumnCodec.TERM_STAT_REC, 3, 201L));

        long[] keys = {10L, 20L, 30L, 40L};
        int[] tfs = {1, 2, 3, 4};
        byte[] tf = SegmentColumnCodec.packTf(keys, tfs);
        Assert.assertEquals(4 * SegmentColumnCodec.TF_REC, tf.length);
        ByteBuffer fb = ByteBuffer.wrap(tf);
        for (int i = 0; i < 4; i++) {
            Assert.assertEquals(keys[i], fb.getLong(i * SegmentColumnCodec.TF_REC));
            Assert.assertEquals(tfs[i], fb.getInt(i * SegmentColumnCodec.TF_REC + 8));
        }
        Assert.assertEquals(3, SegmentColumnCodec.binarySearchKey(fb, SegmentColumnCodec.TF_REC, 4, 40L));
    }

    /* -------- TR1.5 packDynamic / readVar + codec 5 IAE -------- */
    @Test
    public void tr15_packDynamicAndIae() {
        byte[] big = new byte[6000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) ('a' + (i % 7));
        }
        byte[] small = {1, 2, 3};
        SegmentColumnCodec.Dynamic d = SegmentColumnCodec.packDynamic(new byte[][]{big, small, null}, 4096);
        Assert.assertEquals(3, d.off.length);
        Assert.assertEquals(6000, d.len[0]);
        Assert.assertEquals(3, d.len[1]);
        Assert.assertEquals(0, d.len[2]);
        ByteBuffer var = ByteBuffer.wrap(d.data);
        Assert.assertArrayEquals(big, SegmentColumnCodec.readVar(var, d.off[0], d.len[0]));
        Assert.assertArrayEquals(small, SegmentColumnCodec.readVar(var, d.off[1], d.len[1]));
        Assert.assertEquals(0, SegmentColumnCodec.readVar(var, d.off[2], d.len[2]).length);
        // 压缩确有收益（6000B 周期串 → LZ4）
        Assert.assertTrue("dynamic should compress", d.data.length < big.length);

        // codec 5 IAE
        try { SegmentColumnCodec.packDocStat(null, new int[0][], new int[0][]); Assert.fail("null IAE"); }
        catch (IllegalArgumentException ignore) {}
        try { SegmentColumnCodec.packDocStat(new long[]{1L}, new int[][]{{1, 2, 3, 4, 5, 6}}, new int[0][]); Assert.fail("len IAE"); }
        catch (IllegalArgumentException ignore) {}
        try { SegmentColumnCodec.packTf(new long[]{5L, 3L}, new int[]{1, 1}); Assert.fail("asc IAE"); }
        catch (IllegalArgumentException ignore) {}
        try { SegmentColumnCodec.packDynamic(new byte[][]{{1}}, 1024); Assert.fail("pageSize IAE"); }
        catch (IllegalArgumentException ignore) {}
        try {
            SegmentColumnCodec.packDocStat(new long[]{1L}, new int[][]{{1, 2, 3}}, new int[][]{{0, 0, 0, 0, 0, 0}});
            Assert.fail("record IAE");
        } catch (IllegalArgumentException ignore) {}
    }
}
