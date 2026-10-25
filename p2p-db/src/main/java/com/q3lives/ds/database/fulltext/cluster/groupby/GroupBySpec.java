package com.q3lives.ds.database.fulltext.cluster.groupby;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;

/**
 * GroupBySpec 不可变结构（P41 OQ1 DSL v3 聚合 v2 —— GROUP BY / HAVING / DISTINCT）：分组规格。
 *
 * <p>
 * 组合（非继承）复用 P38 {@link AggQueryPlan}（0 改，R4），追加三项分组专属配置：
 * <ul>
 * <li>{@link #groupColumns}：分组列名列表（不可变；非空、无 null / 空串、无重复）</li>
 * <li>{@link #distinctAggIndexes}：标记「哪些聚合项走 {@code COUNT(DISTINCT col)}」的下标集合
 * （不可变；可为空；每项 ∈ [0, {@code plan.aggs.size()}) 且对应 {@code AggSpec.fn == COUNT}）</li>
 * <li>{@link #havings}：桶级过滤谓词列表（不可变；可为空；多谓词 <b>AND</b> 语义；
 * 每项 {@code aggIndex} ∈ [0, {@code plan.aggs.size()})）</li>
 * </ul>
 *
 * <p>
 * {@code plan.aggs} 为空亦合法：此时分组退化为「按分组列去重」（{@code SELECT DISTINCT groupColumns}），
 * 每桶 {@code values} 为空。{@code groupColumns} 为空则 IAE（全局聚合归 P39 {@code AggExecutor}，
 * 避免语义重复）。
 *
 * <p>
 * 6 IAE 守卫（构造即校验，构造后不可变）：
 * <ol>
 * <li>{@code plan == null} → IAE</li>
 * <li>{@code groupColumns == null} 或为空 → IAE</li>
 * <li>{@code groupColumns} 含 {@code null} 或空白串 → IAE</li>
 * <li>{@code groupColumns} 有重复 → IAE</li>
 * <li>{@code distinctAggIndexes} 含 null / 越界 / 对应 {@code fn != COUNT}；{@code havings} 含 null
 * 或 {@code aggIndex} 越界 → IAE</li>
 * <li>构造后四个字段均 {@code unmodifiable} → 后续修改抛 {@code UnsupportedOperationException}</li>
 * </ol>
 *
 * <p>
 * {@link #fingerprint()} 输出 64-char hex SHA-256（{@code plan.fingerprint()} + 分组列 + DISTINCT 下标
 * + HAVING 序列），跨 run / 跨进程稳定（R9）。
 *
 * @author SP3.1 Implement Agent
 * @since P41 OQ1 DSL v3 聚合 v2 MVP
 */
public final class GroupBySpec {

    /** 分组执行计划（P38 复用，非 null）。 */
    public final AggQueryPlan plan;

    /** 分组列名（不可变，非空、无 null / 空白、无重复）。 */
    public final List<String> groupColumns;

    /** {@code COUNT(DISTINCT)} 标记的聚合项下标（不可变，可为空，升序保持输入序）。 */
    public final List<Integer> distinctAggIndexes;

    /** HAVING 谓词（不可变，可为空，多谓词 AND）。 */
    public final List<HavingSpec> havings;

    /**
     * 构造 GroupBySpec 不可变实例（6 IAE 全检）。
     *
     * @param plan 分组执行计划（非 null）
     * @param groupColumns 分组列名（非 null 非空，无 null / 空白 / 重复）
     * @param distinctAggIndexes COUNT(DISTINCT) 下标（非 null，可为空，无 null / 越界 / 非 COUNT）
     * @param havings HAVING 谓词（非 null，可为空，无 null / aggIndex 越界）
     * @throws IllegalArgumentException 任一 IAE 触发
     */
    public GroupBySpec(AggQueryPlan plan, List<String> groupColumns,
            List<Integer> distinctAggIndexes, List<HavingSpec> havings) {
        if (plan == null) {
            throw new IllegalArgumentException("GroupBySpec plan cannot be null");
        }
        if (groupColumns == null) {
            throw new IllegalArgumentException("GroupBySpec groupColumns cannot be null");
        }
        if (groupColumns.isEmpty()) {
            throw new IllegalArgumentException("GroupBySpec groupColumns 不能为空（全局聚合请用 P39 AggExecutor）");
        }
        if (distinctAggIndexes == null) {
            throw new IllegalArgumentException("GroupBySpec distinctAggIndexes cannot be null");
        }
        if (havings == null) {
            throw new IllegalArgumentException("GroupBySpec havings cannot be null");
        }
        int aggCount = plan.aggs.size();
        Set<String> seenCols = new HashSet<>();
        for (int i = 0; i < groupColumns.size(); i++) {
            String c = groupColumns.get(i);
            if (c == null) {
                throw new IllegalArgumentException("GroupBySpec groupColumns[" + i + "] cannot be null");
            }
            if (c.trim().isEmpty()) {
                throw new IllegalArgumentException("GroupBySpec groupColumns[" + i + "] 不能为空白串");
            }
            if (!seenCols.add(c)) {
                throw new IllegalArgumentException("GroupBySpec groupColumns 存在重复列: " + c);
            }
        }
        for (int i = 0; i < distinctAggIndexes.size(); i++) {
            Integer idx = distinctAggIndexes.get(i);
            if (idx == null) {
                throw new IllegalArgumentException("GroupBySpec distinctAggIndexes[" + i + "] cannot be null");
            }
            if (idx < 0 || idx >= aggCount) {
                throw new IllegalArgumentException("GroupBySpec distinctAggIndexes[" + i + "]=" + idx
                        + " 越界（aggs.size=" + aggCount + "）");
            }
            AggSpec spec = plan.aggs.get(idx);
            if (spec.fn != AggFn.COUNT) {
                throw new IllegalArgumentException("GroupBySpec COUNT(DISTINCT) 仅支持 COUNT，aggs["
                        + idx + "]=" + spec + " 非法");
            }
        }
        for (int i = 0; i < havings.size(); i++) {
            HavingSpec h = havings.get(i);
            if (h == null) {
                throw new IllegalArgumentException("GroupBySpec havings[" + i + "] cannot be null");
            }
            if (h.aggIndex >= aggCount) {
                throw new IllegalArgumentException("GroupBySpec havings[" + i + "].aggIndex=" + h.aggIndex
                        + " 越界（aggs.size=" + aggCount + "）");
            }
        }
        this.plan = plan;
        this.groupColumns = Collections.unmodifiableList(new ArrayList<>(groupColumns));
        this.distinctAggIndexes = Collections.unmodifiableList(new ArrayList<>(distinctAggIndexes));
        this.havings = Collections.unmodifiableList(new ArrayList<>(havings));
    }

    /**
     * 判断某聚合项下标是否标记为 {@code COUNT(DISTINCT)}。
     *
     * @param aggIndex 聚合项下标
     * @return 是 {@code true}
     */
    public boolean isDistinct(int aggIndex) {
        return distinctAggIndexes.contains(aggIndex);
    }

    /**
     * 64-char hex SHA-256 指纹（{@code plan.fingerprint()} + 分组列 + DISTINCT 下标 + HAVING 序列），
     * 跨 run / 跨进程稳定。
     *
     * @return 64-char hex 指纹
     */
    public String fingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(plan.fingerprint().getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            for (String c : groupColumns) {
                md.update(c.getBytes(StandardCharsets.UTF_8));
                md.update((byte) ',');
            }
            md.update((byte) '|');
            for (Integer idx : distinctAggIndexes) {
                md.update(Integer.toString(idx).getBytes(StandardCharsets.UTF_8));
                md.update((byte) ',');
            }
            md.update((byte) '|');
            for (HavingSpec h : havings) {
                md.update(Integer.toString(h.aggIndex).getBytes(StandardCharsets.UTF_8));
                md.update((byte) ':');
                md.update((byte) h.op.name().charAt(0));
                md.update((byte) ':');
                md.update(Long.toString(Double.doubleToLongBits(h.threshold)).getBytes(StandardCharsets.UTF_8));
                md.update((byte) ',');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    @Override
    public String toString() {
        return "GroupBySpec[GROUP BY" + groupColumns + " DISTINCT" + distinctAggIndexes
                + " HAVING" + havings + " " + plan + "]";
    }
}
