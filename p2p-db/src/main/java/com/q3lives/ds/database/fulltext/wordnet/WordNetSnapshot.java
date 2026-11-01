package com.q3lives.ds.database.fulltext.wordnet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * P51 跨段词网「导出快照」不可变描述（Word Net Snapshot Descriptor）。
 *
 * <p>
 * 一次 {@link WordNetExporter#export} 的产物摘要：记录词网段 id 与三类统计量
 * （上下文键数 / L/R 邻接条目数 / positions 命中区间数），并携带词网段文件头的 SHA-256 指纹。
 * 本类为纯值对象，全部字段 {@code public final}，构造即校验（6 条 IAE），并提供跨进程可复现的
 * {@link #fingerprint()} 与逐字段 {@link #deepEquals(Object)}。
 *
 * <p>
 * 「词网」语义由词网段格式天然承载：键沿用 P21 既有 {@code mix4} 口径
 * （邻接键 = {@code mix4(docId,parentId,level,srcUid)}），因此本快照无需任何额外维度字段即可完整描述
 * 一次词网段化结果（R5：DIDX 段格式 0 变更，词网段为独立工件）。
 *
 * <p>
 * 6 条 IAE 守卫：① segId &lt; 0 ② ctxCount &lt; 0 ③ adjCount &lt; 0 ④ posCount &lt; 0
 * ⑤ segmentFingerprint 为 null ⑥ segmentFingerprint 长度 ≠ 64。
 *
 * <p>
 * 红线：本类位于新子包 {@code fulltext.wordnet}，不新增 fulltext 根包 top-level 类（ZeroLeak3=3）；
 * 仅 JDK import（R2 NG-3）。
 */
public final class WordNetSnapshot {

    /** 词网段 id（对应 {@code seg-<segId>.dnet}）。 */
    public final long segId;
    /** 段内上下文键记录数（distinct {@code mix4(docId,parentId,level,srcUid)}）。 */
    public final long ctxCount;
    /** 段内 L/R 邻接条目总数（所有上下文键之和）。 */
    public final long adjCount;
    /** 段内 positions 命中区间总数（所有位置键之和）。 */
    public final long posCount;
    /** 词网段文件头指纹（64 位小写 hex）。 */
    public final String segmentFingerprint;

    /**
     * 构造不可变导出快照（构造即校验 6 条不变量）。
     *
     * @param segId               段 id，≥ 0
     * @param ctxCount            上下文键记录数，≥ 0
     * @param adjCount            L/R 邻接条目数，≥ 0
     * @param posCount            positions 命中区间数，≥ 0
     * @param segmentFingerprint  段头指纹，非 null 且长度 = 64
     */
    public WordNetSnapshot(long segId, long ctxCount, long adjCount, long posCount, String segmentFingerprint) {
        if (segId < 0) {
            throw new IllegalArgumentException("snapshot segId < 0: " + segId);
        }
        if (ctxCount < 0) {
            throw new IllegalArgumentException("snapshot ctxCount < 0: " + ctxCount);
        }
        if (adjCount < 0) {
            throw new IllegalArgumentException("snapshot adjCount < 0: " + adjCount);
        }
        if (posCount < 0) {
            throw new IllegalArgumentException("snapshot posCount < 0: " + posCount);
        }
        if (segmentFingerprint == null) {
            throw new IllegalArgumentException("snapshot segmentFingerprint null");
        }
        if (segmentFingerprint.length() != 64) {
            throw new IllegalArgumentException("snapshot segmentFingerprint must be 64 hex, got "
                    + segmentFingerprint.length());
        }
        this.segId = segId;
        this.ctxCount = ctxCount;
        this.adjCount = adjCount;
        this.posCount = posCount;
        this.segmentFingerprint = segmentFingerprint;
    }

    /**
     * 快照指纹：对 {@code segId|ctxCount|adjCount|posCount|segmentFingerprint} 做 SHA-256，
     * 返回 64 位小写 hex。
     *
     * <p>同一字段组合在任意机器 / 任意 run 上指纹一致；任一字段变化即指纹变化，可用于跨进程比对。
     */
    public String fingerprint() {
        String src = segId + "|" + ctxCount + "|" + adjCount + "|" + posCount + "|" + segmentFingerprint;
        return WordNetSnapshot.sha256Hex(src.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 逐字段深比较（段描述等值判定，不依赖 {@code equals}）。
     *
     * @param o 待比较对象
     * @return 五字段全等返回 true
     */
    public boolean deepEquals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WordNetSnapshot)) {
            return false;
        }
        WordNetSnapshot other = (WordNetSnapshot) o;
        return segId == other.segId && ctxCount == other.ctxCount && adjCount == other.adjCount
                && posCount == other.posCount && segmentFingerprint.equals(other.segmentFingerprint);
    }

    /**
     * 计算 SHA-256 小写 hex（64 位）。
     *
     * @param data 待摘要字节，非 null
     */
    static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
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
