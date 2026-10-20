package com.q3lives.ds.database.fulltext.segment;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

import com.q3lives.ds.database.columnar.ColumnCompression;

/**
 * P37 列式段编解码工具（定长列 pack/unpack + 动态区逐块 LZ4 + mmap 二分）。
 *
 * <p>
 * 段内三类统计列均为「键 + 定长负载」的紧凑记录，全部按键严格升序落盘， 因此
 * {@link #binarySearchKey(ByteBuffer, int, long, long)} 可直接在 mmap 的
 * {@link ByteBuffer} 上做 O(log N) 定位 —— 这是「零 startup 重建」的核心： 读取端不建哈希表、不重算
 * idf，仅靠二分。
 *
 * <p>
 * 记录布局（大端序）：
 * <pre>
 * docStat : [docId 8B][docLen[0..5] 6×4B][nTerms[0..5] 6×4B]           = 56B
 * termStat: [uid 8B][idf 8B][payloadOff 4B][payloadLen 4B]              = 24B
 * tf      : [key 8B][tf 4B]                                             = 12B
 * var 块  : [1B flag(0=raw,1=lz4)][4B storedLen][stored bytes]
 * </pre>
 *
 * <p>
 * 动态区压缩复用 first-party {@link ColumnCompression}（LZ4，0 新增 Maven dep）； 压缩后无收益时按
 * raw 存储，保证不膨胀。
 */
public final class SegmentColumnCodec {

    /**
     * docStat 键（docId）宽度。
     */
    public static final int DOC_STAT_KEY = 8;
    /**
     * docStat 负载宽度：docLen[6] + nTerms[6]。
     */
    public static final int DOC_STAT_PAYLOAD = 48;
    /**
     * docStat 整记录宽度（键 + 负载）。
     */
    public static final int DOC_STAT_REC = DOC_STAT_KEY + DOC_STAT_PAYLOAD;

    /**
     * termStat 记录宽度：uid + idf + payloadOff + payloadLen。
     */
    public static final int TERM_STAT_REC = 24;
    /**
     * tf 记录宽度：key + tf。
     */
    public static final int TF_REC = 12;

    /**
     * 单块 raw 上限硬下限（R9：4KB 页对齐）。
     */
    public static final int MIN_PAGE = 4096;

    private static final byte FLAG_RAW = 0;
    private static final byte FLAG_LZ4 = 1;

    private SegmentColumnCodec() {
    }

    /**
     * 打包 docStat 定长列（按 docId 升序）。
     *
     * @param docIds 非空且严格升序
     * @param docLen 每条长度必须为 6
     * @param nTerms 每条长度必须为 6
     * @return 长度为 {@code docIds.length * DOC_STAT_REC} 的字节数组
     */
    public static byte[] packDocStat(long[] docIds, int[][] docLen, int[][] nTerms) {
        if (docIds == null || docLen == null || nTerms == null) {
            throw new IllegalArgumentException("packDocStat null array");
        }
        if (docIds.length != docLen.length || docIds.length != nTerms.length) {
            throw new IllegalArgumentException("packDocStat length mismatch");
        }
        checkStrictAscending(docIds);
        ByteBuffer bb = ByteBuffer.allocate(docIds.length * DOC_STAT_REC);
        for (int i = 0; i < docIds.length; i++) {
            if (docLen[i] == null || docLen[i].length != 6 || nTerms[i] == null || nTerms[i].length != 6) {
                throw new IllegalArgumentException("packDocStat record must be 6+6 ints at i=" + i);
            }
            bb.putLong(docIds[i]);
            for (int k = 0; k < 6; k++) {
                bb.putInt(docLen[i][k]);
            }
            for (int k = 0; k < 6; k++) {
                bb.putInt(nTerms[i][k]);
            }
        }
        return bb.array();
    }

    /**
     * 打包 termStat 定长列（按 uid 升序）。
     */
    public static byte[] packTermStat(long[] uids, double[] idf, int[] payloadOff, int[] payloadLen) {
        if (uids == null || idf == null || payloadOff == null || payloadLen == null) {
            throw new IllegalArgumentException("packTermStat null array");
        }
        if (uids.length != idf.length || uids.length != payloadOff.length || uids.length != payloadLen.length) {
            throw new IllegalArgumentException("packTermStat length mismatch");
        }
        checkStrictAscending(uids);
        ByteBuffer bb = ByteBuffer.allocate(uids.length * TERM_STAT_REC);
        for (int i = 0; i < uids.length; i++) {
            if (payloadOff[i] < 0 || payloadLen[i] < 0) {
                throw new IllegalArgumentException("packTermStat negative payload at i=" + i);
            }
            bb.putLong(uids[i]);
            bb.putDouble(idf[i]);
            bb.putInt(payloadOff[i]);
            bb.putInt(payloadLen[i]);
        }
        return bb.array();
    }

    /**
     * 打包 tf 定长列（按 mix4 key 升序）。
     */
    public static byte[] packTf(long[] keys, int[] tfs) {
        if (keys == null || tfs == null) {
            throw new IllegalArgumentException("packTf null array");
        }
        if (keys.length != tfs.length) {
            throw new IllegalArgumentException("packTf length mismatch");
        }
        checkStrictAscending(keys);
        ByteBuffer bb = ByteBuffer.allocate(keys.length * TF_REC);
        for (int i = 0; i < keys.length; i++) {
            if (tfs[i] < 0) {
                throw new IllegalArgumentException("packTf negative tf at i=" + i);
            }
            bb.putLong(keys[i]);
            bb.putInt(tfs[i]);
        }
        return bb.array();
    }

    /**
     * 动态压缩区打包结果：data 为段文件 var section 字节；off/len 为每条 payload 的定位。
     */
    public static final class Dynamic {

        public final byte[] data;
        public final int[] off;
        public final int[] len;

        Dynamic(byte[] data, int[] off, int[] len) {
            this.data = data;
            this.off = off;
            this.len = len;
        }
    }

    /**
     * 打包动态压缩区：每条 payload 按 {@code pageSize} 上限切块，逐块 LZ4。
     *
     * @param payloads 允许 null 元素（视作 0 长）
     * @param pageSize 单块 raw 上限，必须 ≥ {@link #MIN_PAGE}
     */
    public static Dynamic packDynamic(byte[][] payloads, int pageSize) {
        if (payloads == null) {
            throw new IllegalArgumentException("packDynamic null payloads");
        }
        if (pageSize < MIN_PAGE) {
            throw new IllegalArgumentException("packDynamic pageSize < " + MIN_PAGE + ": " + pageSize);
        }
        byte[] buf = new byte[64];
        int total = 0;
        int[] off = new int[payloads.length];
        int[] len = new int[payloads.length];
        for (int i = 0; i < payloads.length; i++) {
            byte[] p = payloads[i];
            int rawLen = p == null ? 0 : p.length;
            off[i] = total;
            len[i] = rawLen;
            int pos = 0;
            while (pos < rawLen) {
                int chunk = Math.min(pageSize, rawLen - pos);
                byte[] framed = frameBlock(p, pos, chunk);
                if (total + framed.length > buf.length) {
                    buf = grow(buf, total + framed.length);
                }
                System.arraycopy(framed, 0, buf, total, framed.length);
                total += framed.length;
                pos += chunk;
            }
        }
        byte[] data = new byte[total];
        System.arraycopy(buf, 0, data, 0, total);
        return new Dynamic(data, off, len);
    }

    /**
     * 从动态区读取一条 payload（可能跨多块），返回原始字节。
     *
     * @param var var section 的 mmap 视图
     * @param off 起始块偏移
     * @param rawLen 期望原始长度
     */
    public static byte[] readVar(ByteBuffer var, int off, int rawLen) {
        if (var == null) {
            throw new IllegalArgumentException("readVar null buffer");
        }
        if (off < 0 || rawLen < 0) {
            throw new IllegalArgumentException("readVar negative off/len: " + off + "/" + rawLen);
        }
        byte[] out = new byte[rawLen];
        int written = 0;
        int p = off;
        while (written < rawLen) {
            if (p + 5 > var.limit()) {
                throw new IllegalArgumentException("readVar truncated block header at " + p);
            }
            byte flag = var.get(p);
            int storedLen = var.getInt(p + 1);
            if (storedLen < 0 || p + 5 + storedLen > var.limit()) {
                throw new IllegalArgumentException("readVar truncated block body at " + p + " storedLen=" + storedLen);
            }
            byte[] stored = new byte[storedLen];
            for (int k = 0; k < storedLen; k++) {
                stored[k] = var.get(p + 5 + k);
            }
            byte[] chunk;
            if (flag == FLAG_LZ4) {
                chunk = ColumnCompression.decompress(stored);
                if (chunk == null) {
                    throw new IllegalArgumentException("readVar lz4 block corrupt at " + p);
                }
            } else if (flag == FLAG_RAW) {
                chunk = stored;
            } else {
                throw new IllegalArgumentException("readVar unknown block flag=" + flag + " at " + p);
            }
            if (written + chunk.length > rawLen) {
                throw new IllegalArgumentException(
                        "readVar payload overrun: written=" + written + " chunk=" + chunk.length + " rawLen=" + rawLen);
            }
            System.arraycopy(chunk, 0, out, written, chunk.length);
            written += chunk.length;
            p += 5 + storedLen;
        }
        return out;
    }

    /**
     * 在定长列上二分查找键，返回记录下标；未命中返回 -1。
     *
     * @param col 定长列 mmap 视图
     * @param recSize 单记录宽度
     * @param count 记录数
     * @param key 目标键
     */
    public static int binarySearchKey(ByteBuffer col, int recSize, long count, long key) {
        if (col == null) {
            throw new IllegalArgumentException("binarySearchKey null buffer");
        }
        if (recSize <= 0 || count < 0) {
            throw new IllegalArgumentException("binarySearchKey bad recSize/count: " + recSize + "/" + count);
        }
        int lo = 0;
        int hi = (int) count - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = col.getLong(mid * recSize);
            if (v < key) {
                lo = mid + 1;
            } else if (v > key) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /**
     * 序列化段头（固定 {@link DsIndexSegment#HEADER_SIZE} 字节，4KB 页对齐，R9）。
     *
     * <p>
     * 前 112 字节为大端定长字段区，其余补零。
     */
    public static byte[] writeHeader(DsIndexSegment seg) {
        if (seg == null) {
            throw new IllegalArgumentException("writeHeader null segment");
        }
        ByteBuffer bb = ByteBuffer.allocate(DsIndexSegment.HEADER_SIZE);
        bb.put(DsIndexSegment.MAGIC);
        bb.putInt(seg.version);
        bb.putLong(seg.docCount);
        bb.putLong(seg.termCount);
        bb.putLong(seg.tfCount);
        bb.putLong(seg.docStatOff);
        bb.putLong(seg.docStatLen);
        bb.putLong(seg.termStatOff);
        bb.putLong(seg.termStatLen);
        bb.putLong(seg.tfOff);
        bb.putLong(seg.tfLen);
        bb.putLong(seg.varOff);
        bb.putLong(seg.varLen);
        for (int c : seg.crc32) {
            bb.putInt(c);
        }
        return bb.array();
    }

    /**
     * 解析并校验段头（R7：magic/version 硬校验；长度不足即 IAE）。
     *
     * @param buf 至少包含 {@link DsIndexSegment#HEADER_SIZE} 字节的段文件视图
     */
    public static DsIndexSegment readHeader(ByteBuffer buf) {
        if (buf == null) {
            throw new IllegalArgumentException("readHeader null buffer");
        }
        if (buf.limit() < DsIndexSegment.HEADER_SIZE) {
            throw new IllegalArgumentException("readHeader short file: " + buf.limit());
        }
        for (int i = 0; i < DsIndexSegment.MAGIC.length; i++) {
            if (buf.get(i) != DsIndexSegment.MAGIC[i]) {
                throw new IllegalArgumentException("readHeader bad magic at " + i);
            }
        }
        int version = buf.getInt(4);
        long docCount = buf.getLong(8);
        long termCount = buf.getLong(16);
        long tfCount = buf.getLong(24);
        long docStatOff = buf.getLong(32);
        long docStatLen = buf.getLong(40);
        long termStatOff = buf.getLong(48);
        long termStatLen = buf.getLong(56);
        long tfOff = buf.getLong(64);
        long tfLen = buf.getLong(72);
        long varOff = buf.getLong(80);
        long varLen = buf.getLong(88);
        int[] crcs = new int[DsIndexSegment.CRC_SECTIONS];
        for (int i = 0; i < crcs.length; i++) {
            crcs[i] = buf.getInt(96 + i * 4);
        }
        return new DsIndexSegment(version, docCount, termCount, tfCount,
                docStatOff, docStatLen, termStatOff, termStatLen, tfOff, tfLen, varOff, varLen, crcs);
    }

    /**
     * 计算 CRC32。
     */
    public static int crc32(byte[] data) {
        CRC32 c = new CRC32();
        if (data != null) {
            c.update(data, 0, data.length);
        }
        return (int) c.getValue();
    }

    /**
     * 校验键数组严格升序（重复键不合法）。
     */
    public static void checkStrictAscending(long[] keys) {
        for (int i = 1; i < keys.length; i++) {
            if (keys[i] <= keys[i - 1]) {
                throw new IllegalArgumentException(
                        "keys must be strictly ascending at i=" + i + ": prev=" + keys[i - 1] + " cur=" + keys[i]);
            }
        }
    }

    private static byte[] frameBlock(byte[] src, int off, int len) {
        byte[] raw = new byte[len];
        System.arraycopy(src, off, raw, 0, len);
        byte[] compressed = ColumnCompression.compress(raw, 1);
        if (compressed != null) {
            ByteBuffer bb = ByteBuffer.allocate(5 + compressed.length);
            bb.put(FLAG_LZ4);
            bb.putInt(compressed.length);
            bb.put(compressed);
            return bb.array();
        }
        ByteBuffer bb = ByteBuffer.allocate(5 + len);
        bb.put(FLAG_RAW);
        bb.putInt(len);
        bb.put(raw);
        return bb.array();
    }

    private static byte[] grow(byte[] buf, int need) {
        int cap = buf.length;
        while (cap < need) {
            cap <<= 1;
        }
        byte[] n = new byte[cap];
        System.arraycopy(buf, 0, n, 0, buf.length);
        return n;
    }
}
