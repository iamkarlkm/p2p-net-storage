package com.q3lives.ds.database.fulltext.compact;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * P40 段合并触发策略（size-tiered LSM compaction policy）。
 *
 * <p>
 * 按段字节体积做 <b>size-tiered</b> 分层：{@code tier = floor(log_4(sizeBytes))}，即每层的体积
 * 区间按 4 倍递增（tier0 覆盖 [1,4) 字节、tier1 [4,16)、tier2 [16,64) …）。同一层内累计段数达到
 * {@code fanIn} 即触发一次合并，取该层<b>最早</b>的 {@code maxFanIn} 个段（因输入 segId 严格升序，
 * 「最早」即最小 segId），从而把 N 个小段收敛为 1 个大段，控制查询期的段数与二分次数。
 *
 * <p>
 * 本类是<b>纯决策函数</b>：无 IO、无副作用、无共享状态；同一输入恒得同一输出（确定性），便于跨进程
 * 复现与单测。真正的写盘/删段由 {@code SegmentCompactionRunner} 承担，策略只产出「该合并哪些段」。
 *
 * <p>4 条 IAE 守卫：① segIds / segBytes 为 null 或长度不等 ② fanIn &lt; 2 ③ maxFanIn &lt; fanIn
 * ④ segIds 非严格升序（含重复）。
 *
 * <p>红线：本类位于新子包 {@code fulltext.compact}（ZeroLeak3=3 永保）。
 */
public final class SegmentCompactionPolicy {

    private SegmentCompactionPolicy() {
    }

    /**
     * 计算 size-tiered 分层并选出待合并段组。
     *
     * @param segIds   已注册段 id 数组（严格升序）
     * @param segBytes 各段字节体积，与 segIds 一一对应
     * @param fanIn    层内触发阈值，≥ 2
     * @param maxFanIn 单次合并纳入的段数上限，≥ fanIn
     * @return 待合并段组列表（按 tier 升序；每组为层内最早的 maxFanIn 个 segId，升序）；无触发返回空列表
     */
    public static List<long[]> selectTiers(long[] segIds, long[] segBytes, int fanIn, int maxFanIn) {
        if (segIds == null || segBytes == null) {
            throw new IllegalArgumentException("policy segIds/segBytes null");
        }
        if (segIds.length != segBytes.length) {
            throw new IllegalArgumentException(
                    "policy length mismatch: ids=" + segIds.length + " bytes=" + segBytes.length);
        }
        if (fanIn < 2) {
            throw new IllegalArgumentException("policy fanIn < 2: " + fanIn);
        }
        if (maxFanIn < fanIn) {
            throw new IllegalArgumentException("policy maxFanIn < fanIn: " + maxFanIn + " < " + fanIn);
        }
        for (int i = 1; i < segIds.length; i++) {
            if (segIds[i] <= segIds[i - 1]) {
                throw new IllegalArgumentException(
                        "policy segIds not strictly ascending at i=" + i + ": " + segIds[i - 1] + " -> " + segIds[i]);
            }
        }
        Map<Integer, List<Long>> byTier = new TreeMap<>();
        for (int i = 0; i < segIds.length; i++) {
            byTier.computeIfAbsent(tierOf(segBytes[i]), k -> new ArrayList<>()).add(segIds[i]);
        }
        List<long[]> groups = new ArrayList<>();
        for (List<Long> ids : byTier.values()) {
            if (ids.size() >= fanIn) {
                long[] group = new long[maxFanIn];
                for (int k = 0; k < maxFanIn; k++) {
                    group[k] = ids.get(k);
                }
                groups.add(group);
            }
        }
        return groups;
    }

    /**
     * size-tiered 层号：{@code floor(log_4(sizeBytes))}；非正体积归入 tier 0。
     */
    private static int tierOf(long sizeBytes) {
        int tier = 0;
        long v = sizeBytes;
        while (v >= 4) {
            v /= 4;
            tier++;
        }
        return tier;
    }
}
