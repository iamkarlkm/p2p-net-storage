package com.q3lives.ds.database.fulltext.compact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * P40 不可变段合并计划（Immutable Compaction Plan）。
 *
 * <p>
 * 承载一次合并的完整决策：目标层 {@link #tier}、参与合并的源段 id 组 {@link #sourceSegIds}（严格
 * 升序副本，升序即「旧 → 新」）、以及产物段 id {@link #mergedSegId}。本类为纯值对象：构造即校验并
 * 对源 id 数组做防御性拷贝，字段全部 {@code public final}，故不可变、可安全跨线程/跨进程传递。
 *
 * <p>
 * {@link #fingerprint()} 仅覆盖（tier + 源 segId 升序集 + mergedSegId），与段正文无关，因此同一
 * 合并决策在任意机器 / 任意 run 上指纹完全一致（R10 计划确定性），可用于跨进程比对与幂等判定。
 *
 * <p>4 条 IAE 守卫：① tier &lt; 0 ② sourceSegIds 为 null / 空 ③ sourceSegIds 非严格升序
 * ④ mergedSegId &lt; 0 或落在 sourceSegIds 内（自合并无意义）。
 *
 * <p>红线：本类位于新子包 {@code fulltext.compact}（ZeroLeak3=3 永保）。
 */
public final class SegmentCompactionPlan {

    /** size-tiered 层号，≥ 0。 */
    public final int tier;

    /** 参与合并的源段 id（严格升序 = 旧 → 新），构造时已拷贝。 */
    public final long[] sourceSegIds;

    /** 产物段 id，≥ 0 且不等于任一源段 id。 */
    public final long mergedSegId;

    /**
     * 构造合并计划（构造即校验 4 条不变量）。
     *
     * @param tier         目标层号，≥ 0
     * @param sourceSegIds 源段 id 数组，非 null、非空、严格升序
     * @param mergedSegId  产物段 id，≥ 0 且不落在 sourceSegIds 内
     */
    public SegmentCompactionPlan(int tier, long[] sourceSegIds, long mergedSegId) {
        if (tier < 0) {
            throw new IllegalArgumentException("plan tier < 0: " + tier);
        }
        if (sourceSegIds == null || sourceSegIds.length == 0) {
            throw new IllegalArgumentException("plan sourceSegIds null or empty");
        }
        for (int i = 1; i < sourceSegIds.length; i++) {
            if (sourceSegIds[i] <= sourceSegIds[i - 1]) {
                throw new IllegalArgumentException("plan sourceSegIds not strictly ascending at i=" + i);
            }
        }
        if (mergedSegId < 0) {
            throw new IllegalArgumentException("plan mergedSegId < 0: " + mergedSegId);
        }
        for (long id : sourceSegIds) {
            if (id == mergedSegId) {
                throw new IllegalArgumentException("plan mergedSegId in sourceSegIds: " + mergedSegId);
            }
        }
        this.tier = tier;
        this.sourceSegIds = sourceSegIds.clone();
        this.mergedSegId = mergedSegId;
    }

    /**
     * 计划指纹：{@code SHA-256(tier | 源 segId 升序 | mergedSegId)}，64 位小写 hex。
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(tier).append('|');
        for (long id : sourceSegIds) {
            sb.append(id).append(',');
        }
        sb.append('|').append(mergedSegId);
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

    /**
     * 逐字段深比较（含源 id 数组内容）。
     */
    public boolean deepEquals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SegmentCompactionPlan)) {
            return false;
        }
        SegmentCompactionPlan other = (SegmentCompactionPlan) o;
        return tier == other.tier
                && mergedSegId == other.mergedSegId
                && Arrays.equals(sourceSegIds, other.sourceSegIds);
    }

    @Override
    public boolean equals(Object o) {
        return deepEquals(o);
    }

    @Override
    public int hashCode() {
        return (tier * 31 + Arrays.hashCode(sourceSegIds)) * 31 + Long.hashCode(mergedSegId);
    }
}
