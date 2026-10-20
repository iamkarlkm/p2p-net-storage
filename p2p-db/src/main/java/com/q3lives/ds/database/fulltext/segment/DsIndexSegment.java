package com.q3lives.ds.database.fulltext.segment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * P37 不可变「BM25 索引段」元数据（Immutable Index Segment Descriptor）。
 *
 * <p>
 * 描述一段自描述列式段文件的头信息：魔数、版本、三类统计记录数（docStat / termStat / tf）
 * 以及四个 section 的偏移与长度（docStat 定长列、termStat 定长列、tf 定长列、动态压缩区）。
 * 段文件布局固定为「1 页 4KB 头 + 依次 4KB 对齐的 section」，因此本类同时是段文件的
 * 读取入口契约：{@code DsIndexSegmentReader} 仅凭本对象即可 mmap 定位到任意 section，
 * 无需扫描任何上游数据源，实现「零 startup 重建」。
 *
 * <p>
 * 本类为纯值对象：全部字段 {@code public final}，构造即校验（5 条 IAE），
 * 并提供跨进程可复现的 SHA-256 {@link #fingerprint()}。
 *
 * <p>红线：ZeroLeak3 —— 本类位于 {@code fulltext.segment} 子包，不新增 fulltext 根包 top-level 类。
 */
public final class DsIndexSegment {

    /** 段文件魔数：'D' 'I' 'D' 'X'。 */
    public static final byte[] MAGIC = {'D', 'I', 'D', 'X'};

    /** 段格式版本（R7 硬校验：不匹配即 IAE）。 */
    public static final int VERSION = 1;

    /** 段头保留 1 页（R9：4KB 页对齐）。 */
    public static final int HEADER_SIZE = 4096;

    /** 校验和 section 数量（docStat / termStat / tf / var）。 */
    public static final int CRC_SECTIONS = 4;

    public final int version;
    public final long docCount;
    public final long termCount;
    public final long tfCount;
    public final long docStatOff;
    public final long docStatLen;
    public final long termStatOff;
    public final long termStatLen;
    public final long tfOff;
    public final long tfLen;
    public final long varOff;
    public final long varLen;
    public final int[] crc32;

    /**
     * 构造段元数据（构造即校验 5 条不变量）。
     *
     * @param version     段格式版本，必须等于 {@link #VERSION}
     * @param docCount    docStat 记录数，≥ 0
     * @param termCount   termStat 记录数，≥ 0
     * @param tfCount     tf 记录数，≥ 0
     * @param docStatOff  docStat section 起始偏移（字节）
     * @param docStatLen  docStat section 长度（字节）
     * @param termStatOff termStat section 起始偏移（字节）
     * @param termStatLen termStat section 长度（字节）
     * @param tfOff       tf section 起始偏移（字节）
     * @param tfLen       tf section 长度（字节）
     * @param varOff      动态压缩区起始偏移（字节）
     * @param varLen      动态压缩区长度（字节）
     * @param crc32       四段 CRC32，长度必须为 4
     */
    public DsIndexSegment(int version, long docCount, long termCount, long tfCount,
            long docStatOff, long docStatLen, long termStatOff, long termStatLen,
            long tfOff, long tfLen, long varOff, long varLen, int[] crc32) {
        if (version != VERSION) {
            throw new IllegalArgumentException("segment version mismatch: got=" + version + " expect=" + VERSION);
        }
        if (docCount < 0 || termCount < 0 || tfCount < 0) {
            throw new IllegalArgumentException(
                    "segment count negative: doc=" + docCount + " term=" + termCount + " tf=" + tfCount);
        }
        if (docStatOff < 0 || docStatLen < 0 || termStatOff < 0 || termStatLen < 0
                || tfOff < 0 || tfLen < 0 || varOff < 0 || varLen < 0) {
            throw new IllegalArgumentException("segment off/len negative: docStat=" + docStatOff + "/" + docStatLen
                    + " termStat=" + termStatOff + "/" + termStatLen + " tf=" + tfOff + "/" + tfLen
                    + " var=" + varOff + "/" + varLen);
        }
        if (crc32 == null || crc32.length != CRC_SECTIONS) {
            throw new IllegalArgumentException("segment crc32 must have " + CRC_SECTIONS + " entries");
        }
        if (docCount > 0 && docStatLen != docCount * SegmentColumnCodec.DOC_STAT_REC) {
            throw new IllegalArgumentException("segment docStatLen mismatch: len=" + docStatLen
                    + " expect=" + (docCount * SegmentColumnCodec.DOC_STAT_REC));
        }
        this.version = version;
        this.docCount = docCount;
        this.termCount = termCount;
        this.tfCount = tfCount;
        this.docStatOff = docStatOff;
        this.docStatLen = docStatLen;
        this.termStatOff = termStatOff;
        this.termStatLen = termStatLen;
        this.tfOff = tfOff;
        this.tfLen = tfLen;
        this.varOff = varOff;
        this.varLen = varLen;
        this.crc32 = crc32.clone();
    }

    /**
     * 计算段元数据的 SHA-256 指纹（64 位小写 hex）。
     *
     * <p>指纹仅覆盖头字段（含四段 CRC32），不含段正文；因此「同一批次同源统计」生成
     * 的段在任意机器 / 任意 run 上指纹完全一致，可用于跨进程比对。
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder(160);
        sb.append(version).append('|').append(docCount).append('|').append(termCount).append('|').append(tfCount)
                .append('|').append(docStatOff).append('|').append(docStatLen)
                .append('|').append(termStatOff).append('|').append(termStatLen)
                .append('|').append(tfOff).append('|').append(tfLen)
                .append('|').append(varOff).append('|').append(varLen);
        for (int c : crc32) {
            sb.append('|').append(c);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
