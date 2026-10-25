package com.q3lives.ds.database.fulltext.layered;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * P42 分层倒排「导出快照」不可变描述（Layered Snapshot Descriptor）。
 *
 * <p>
 * 一次 {@link LayeredIndexExporter#export} 的产物摘要：记录段 id 与三类统计记录数
 * （docStat / termStat / tf），并携带 P37 段元数据的 SHA-256 指纹。本类为纯值对象，
 * 全部字段 {@code public final}，构造即校验（6 条 IAE），并提供跨进程可复现的
 * {@link #fingerprint()} 与逐字段 {@link #deepEquals(Object)}。
 *
 * <p>
 * 「分层」语义由 P37 段格式天然承载：docStat 列本就是 6 level 定长列
 * （{@code docLen[6] + nTerms[6]}），tf 键本就把 {@code level} 烘焙进
 * {@code mix4(uid,docId,parentId,level)}，因此本快照无需任何 level 维度字段即可完整描述
 * 分层倒排的一次段化结果（R5：段格式 0 变更）。
 *
 * <p>
 * 6 条 IAE 守卫：① segId &lt; 0 ② docCount &lt; 0 ③ termCount &lt; 0 ④ tfCount &lt; 0
 * ⑤ segmentFingerprint 为 null ⑥ segmentFingerprint 长度 ≠ 64。
 *
 * <p>
 * 红线：本类位于新子包 {@code fulltext.layered}，不新增 fulltext 根包 top-level 类
 * （ZeroLeak3=3）；仅 JDK import（R2 NG-3）。
 */
public final class LayeredSnapshot {

    /** 段 id（对应 {@code seg-<segId>.didx}）。 */
    public final long segId;
    /** 段内 docStat 记录数（distinct docId）。 */
    public final long docCount;
    /** 段内 termStat 记录数（distinct uid）。 */
    public final long termCount;
    /** 段内 tf 记录数（distinct mix4 键）。 */
    public final long tfCount;
    /** P37 段元数据指纹（{@code DsIndexSegment.fingerprint()}，64 位小写 hex）。 */
    public final String segmentFingerprint;

    /**
     * 构造不可变导出快照（构造即校验 6 条不变量）。
     *
     * @param segId               段 id，≥ 0
     * @param docCount            docStat 记录数，≥ 0
     * @param termCount           termStat 记录数，≥ 0
     * @param tfCount             tf 记录数，≥ 0
     * @param segmentFingerprint  段元数据指纹，非 null 且长度 = 64
     */
    public LayeredSnapshot(long segId, long docCount, long termCount, long tfCount, String segmentFingerprint) {
        if (segId < 0) {
            throw new IllegalArgumentException("snapshot segId < 0: " + segId);
        }
        if (docCount < 0) {
            throw new IllegalArgumentException("snapshot docCount < 0: " + docCount);
        }
        if (termCount < 0) {
            throw new IllegalArgumentException("snapshot termCount < 0: " + termCount);
        }
        if (tfCount < 0) {
            throw new IllegalArgumentException("snapshot tfCount < 0: " + tfCount);
        }
        if (segmentFingerprint == null) {
            throw new IllegalArgumentException("snapshot segmentFingerprint null");
        }
        if (segmentFingerprint.length() != 64) {
            throw new IllegalArgumentException("snapshot segmentFingerprint must be 64 hex, got "
                    + segmentFingerprint.length());
        }
        this.segId = segId;
        this.docCount = docCount;
        this.termCount = termCount;
        this.tfCount = tfCount;
        this.segmentFingerprint = segmentFingerprint;
    }

    /**
     * 快照指纹：对 {@code segId|docCount|termCount|tfCount|segmentFingerprint} 做 SHA-256，
     * 返回 64 位小写 hex。
     *
     * <p>同一字段组合在任意机器 / 任意 run 上指纹一致；任一字段变化即指纹变化，可用于跨进程比对。
     */
    public String fingerprint() {
        String src = segId + "|" + docCount + "|" + termCount + "|" + tfCount + "|" + segmentFingerprint;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(src.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
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
        if (!(o instanceof LayeredSnapshot)) {
            return false;
        }
        LayeredSnapshot other = (LayeredSnapshot) o;
        return segId == other.segId && docCount == other.docCount && termCount == other.termCount
                && tfCount == other.tfCount && segmentFingerprint.equals(other.segmentFingerprint);
    }
}
