package com.q3lives.ds.database.fulltext.segment;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import com.q3lives.ds.database.fulltext.FulltextStore;

/**
 * P37 BM25 索引段只读读取端（Segment Reader，mmap 零重建）。
 *
 * <p>
 * {@link #open(File)} 仅做三件事：① mmap 整段文件；② 解析并硬校验段头（MAGIC + VERSION，R7）；
 * ③ 逐 section 复算 CRC32 比对（R8）。**不扫描任何上游数据源、不构建堆内哈希表、不重算 idf** ——
 * 段文件自描述，读取完全靠「三列定长区 mmap 二分 + 动态区按需解压」，因此启动代价与段体积无关，
 * 只与「打开一次 + 常数校验」有关。
 *
 * <p>
 * 三列均为按键严格升序落盘，故 {@link SegmentColumnCodec#binarySearchKey} 可在 mmap 视图上直接
 * 做 O(log N) 定位：docStat 命中返回文档统计行，termStat 命中返回 idf + 按需解压的 payload，
 * tf 命中返回值、未命中返回 0（与 P22 {@code WeightStore} 覆盖语义一致）。
 *
 * <p>
 * 资源管理：{@link #close()} 通过反射调用 JDK 内部 unmapper（与 {@code DsObject.unloadBuffer} 同款
 * 双通道：先 {@code MappedByteBuffer.unmapper().unmap()}，失败再退 {@code sun.misc.Unsafe#invokeCleaner}），
 * 确保 Windows 下段文件可被立即删除；close 幂等。
 *
 * <p>4 条 IAE 守卫：① segFile 为 null / 不存在 ② 文件长度 &lt; {@link DsIndexSegment#HEADER_SIZE}
 * ③ MAGIC / VERSION 不匹配 ④ 任一 section CRC32 不匹配。
 *
 * <p>红线：ZeroLeak3 —— 本类位于 {@code fulltext.segment} 子包，不新增 fulltext 根包 top-level 类；
 * R3 TOPK 铁律 —— 纯只读，0 改 K，不触碰任何 bucket 计数。
 */
public final class DsIndexSegmentReader implements AutoCloseable {

    private static final int DOC_BASE = SegmentColumnCodec.DOC_STAT_KEY;

    private static Method UNMAPPER_METHOD;
    private static Object UNSAFE_OBJ;
    private static Method UNSAFE_INVOKE_CLEANER;

    private final DsIndexSegment seg;
    private final MappedByteBuffer map;
    private final ByteBuffer docStat;
    private final ByteBuffer termStat;
    private final ByteBuffer tf;
    private final ByteBuffer var;
    private boolean closed;

    private DsIndexSegmentReader(DsIndexSegment seg, MappedByteBuffer map) {
        this.seg = seg;
        this.map = map;
        this.docStat = slice(map, seg.docStatOff, seg.docStatLen);
        this.termStat = slice(map, seg.termStatOff, seg.termStatLen);
        this.tf = slice(map, seg.tfOff, seg.tfLen);
        this.var = slice(map, seg.varOff, seg.varLen);
    }

    /**
     * 打开段文件（mmap + 头校验 + CRC 校验，零重建）。
     *
     * @param segFile 段文件，非 null 且长度 ≥ {@link DsIndexSegment#HEADER_SIZE}
     */
    public static DsIndexSegmentReader open(File segFile) throws IOException {
        if (segFile == null) {
            throw new IllegalArgumentException("reader segFile null");
        }
        if (!segFile.isFile()) {
            throw new IllegalArgumentException("reader segFile missing: " + segFile.getName());
        }
        if (segFile.length() < DsIndexSegment.HEADER_SIZE) {
            throw new IllegalArgumentException("reader segFile too short: " + segFile.length());
        }
        MappedByteBuffer map;
        try (FileChannel ch = FileChannel.open(segFile.toPath(), StandardOpenOption.READ)) {
            map = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
        ByteBuffer head = map.duplicate();
        head.position(0);
        DsIndexSegment seg = SegmentColumnCodec.readHeader(head);
        verifySection(map, seg.docStatOff, seg.docStatLen, seg.crc32[0], "docStat");
        verifySection(map, seg.termStatOff, seg.termStatLen, seg.crc32[1], "termStat");
        verifySection(map, seg.tfOff, seg.tfLen, seg.crc32[2], "tf");
        verifySection(map, seg.varOff, seg.varLen, seg.crc32[3], "var");
        return new DsIndexSegmentReader(seg, map);
    }

    /**
     * 二分查文档统计行；未命中返回 null。
     */
    public FulltextStore.WeightDocStatRow getDocStat(long docId) {
        ensureOpen();
        int i = SegmentColumnCodec.binarySearchKey(docStat, SegmentColumnCodec.DOC_STAT_REC, seg.docCount, docId);
        if (i < 0) {
            return null;
        }
        int base = i * SegmentColumnCodec.DOC_STAT_REC + DOC_BASE;
        FulltextStore.WeightDocStatRow r = new FulltextStore.WeightDocStatRow();
        for (int k = 0; k < 6; k++) {
            r.docLen[k] = docStat.getInt(base + k * 4);
        }
        for (int k = 0; k < 6; k++) {
            r.nTerms[k] = docStat.getInt(base + 24 + k * 4);
        }
        return r;
    }

    /**
     * 二分查 term 全局统计行（idf + 按需解压 payload）；未命中返回 null。
     */
    public FulltextStore.WeightTermStatRow getTermStat(long uid) {
        ensureOpen();
        int i = SegmentColumnCodec.binarySearchKey(termStat, SegmentColumnCodec.TERM_STAT_REC, seg.termCount, uid);
        if (i < 0) {
            return null;
        }
        int base = i * SegmentColumnCodec.TERM_STAT_REC;
        double idf = termStat.getDouble(base + 8);
        int payloadOff = termStat.getInt(base + 16);
        int payloadLen = termStat.getInt(base + 20);
        return new FulltextStore.WeightTermStatRow(idf, SegmentColumnCodec.readVar(var, payloadOff, payloadLen));
    }

    /**
     * 二分查词频；未命中返回 0（与 P22 WeightStore 语义一致）。
     */
    public int getTf(long key) {
        ensureOpen();
        int i = SegmentColumnCodec.binarySearchKey(tf, SegmentColumnCodec.TF_REC, seg.tfCount, key);
        if (i < 0) {
            return 0;
        }
        return tf.getInt(i * SegmentColumnCodec.TF_REC + 8);
    }

    /**
     * 按位前缀扫描词频列，返回满足 {@code (key & mask) == (prefix & mask)} 的键（升序）。
     */
    public long[] scanTfPrefix(long keyPrefixHighBits, long keyPrefixMask) {
        ensureOpen();
        long want = keyPrefixHighBits & keyPrefixMask;
        long[] tmp = new long[(int) seg.tfCount];
        int n = 0;
        for (int i = 0; i < seg.tfCount; i++) {
            long k = tf.getLong(i * SegmentColumnCodec.TF_REC);
            if ((k & keyPrefixMask) == want) {
                tmp[n++] = k;
            }
        }
        return Arrays.copyOf(tmp, n);
    }

    /**
     * 段描述对象（头信息）。
     */
    public DsIndexSegment segment() {
        return seg;
    }

    /**
     * docStat 记录数。
     */
    public long docCount() {
        return seg.docCount;
    }

    /**
     * termStat 记录数。
     */
    public long termCount() {
        return seg.termCount;
    }

    /**
     * tf 记录数。
     */
    public long tfCount() {
        return seg.tfCount;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        unmap(map);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalArgumentException("reader already closed");
        }
    }

    private static ByteBuffer slice(MappedByteBuffer map, long off, long len) {
        ByteBuffer b = map.duplicate();
        b.position((int) off);
        b.limit((int) (off + len));
        return b.slice();
    }

    private static void verifySection(MappedByteBuffer map, long off, long len, int expectCrc, String name) {
        if (off < 0 || len < 0 || off + len > map.limit()) {
            throw new IllegalArgumentException("segment section out of range [" + name + "]: off=" + off + " len=" + len);
        }
        ByteBuffer b = map.duplicate();
        b.position((int) off);
        b.limit((int) (off + len));
        byte[] data = new byte[(int) len];
        b.get(data);
        int actual = SegmentColumnCodec.crc32(data);
        if (actual != expectCrc) {
            throw new IllegalArgumentException(
                    "segment CRC mismatch [" + name + "]: expect=" + expectCrc + " actual=" + actual);
        }
    }

    private static void unmap(MappedByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        try {
            if (UNMAPPER_METHOD == null) {
                UNMAPPER_METHOD = MappedByteBuffer.class.getDeclaredMethod("unmapper");
                UNMAPPER_METHOD.setAccessible(true);
            }
            Object unmapper = UNMAPPER_METHOD.invoke(buffer);
            if (unmapper != null) {
                Method unmap = unmapper.getClass().getMethod("unmap");
                unmap.invoke(unmapper);
            }
            return;
        } catch (Throwable ignore) {
            // 退化到 Unsafe.invokeCleaner
        }
        try {
            if (UNSAFE_INVOKE_CLEANER == null || UNSAFE_OBJ == null) {
                Class<?> uc = Class.forName("sun.misc.Unsafe");
                Method gf = uc.getDeclaredMethod("getUnsafe");
                gf.setAccessible(true);
                UNSAFE_OBJ = gf.invoke(null);
                UNSAFE_INVOKE_CLEANER = uc.getMethod("invokeCleaner", ByteBuffer.class);
            }
            UNSAFE_INVOKE_CLEANER.invoke(UNSAFE_OBJ, buffer);
        } catch (Throwable ignore) {
            // 无法强制 unmap 时交由 GC 回收
        }
    }
}
