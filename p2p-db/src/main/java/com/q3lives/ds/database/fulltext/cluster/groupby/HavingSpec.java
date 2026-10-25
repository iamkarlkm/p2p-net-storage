package com.q3lives.ds.database.fulltext.cluster.groupby;

import com.q3lives.ds.database.fulltext.cluster.agg.AggValue;

/**
 * HavingSpec 不可变结构（P41 OQ1 DSL v3 聚合 v2 —— GROUP BY / HAVING / DISTINCT）：HAVING 单谓词。
 *
 * <p>
 * 一个 {@code HavingSpec} 表示「对某个聚合项的执行答案做一次比较」，例如
 * {@code HAVING COUNT(price) >= 3} 编码为 {@code new HavingSpec(0, HavingOp.GE, 3.0)}：
 * <ul>
 * <li>{@link #aggIndex}：指向 {@code GroupBySpec.plan.aggs} 的下标（≥ 0，构造期不校验上界，
 * 由 {@link GroupBySpec} 结合 {@code plan.aggs.size()} 统一校验，R10）</li>
 * <li>{@link #op}：比较算子（{@link HavingOp}，非 null）</li>
 * <li>{@link #threshold}：比较阈值（有限，非 NaN / 非 Infinite）</li>
 * </ul>
 *
 * <p>
 * {@link #test(AggValue)} 依据 {@link AggValue#value} 与 {@link #threshold} 按 {@link #op} 判决；
 * 多谓词由 {@code GroupByExecutor} 以 <b>AND</b> 语义组合（本类只管单谓词，不做组合）。
 *
 * <p>
 * 3 IAE 守卫（构造即校验，构造后不可变）：
 * <ol>
 * <li>{@code aggIndex < 0} → IAE</li>
 * <li>{@code op == null} → IAE</li>
 * <li>{@code threshold} 为 {@code NaN} / {@code Infinite} → IAE</li>
 * </ol>
 *
 * <p>
 * {@link #test(AggValue)} 传 {@code null} 一律 {@link IllegalArgumentException}（绝不静默返回
 * {@code false}，R10）。
 *
 * @author SP3.1 Implement Agent
 * @since P41 OQ1 DSL v3 聚合 v2 MVP
 */
public final class HavingSpec {

    /**
     * HAVING 比较算子（内嵌 enum，不计入 top-level 类数，R1）。
     */
    public enum HavingOp {
        /** 大于。 */
        GT,
        /** 大于等于。 */
        GE,
        /** 小于。 */
        LT,
        /** 小于等于。 */
        LE,
        /** 等于。 */
        EQ,
        /** 不等于。 */
        NE
    }

    /** 指向 {@code GroupBySpec.plan.aggs} 的下标（≥ 0）。 */
    public final int aggIndex;

    /** 比较算子（非 null）。 */
    public final HavingOp op;

    /** 比较阈值（有限，非 NaN / 非 Infinite）。 */
    public final double threshold;

    /**
     * 构造 HavingSpec 不可变实例（3 IAE 全检）。
     *
     * @param aggIndex 聚合项下标（≥ 0）
     * @param op 比较算子（非 null）
     * @param threshold 比较阈值（有限）
     * @throws IllegalArgumentException 任一 IAE 触发
     */
    public HavingSpec(int aggIndex, HavingOp op, double threshold) {
        if (aggIndex < 0) {
            throw new IllegalArgumentException("HavingSpec aggIndex 必须 ≥ 0: " + aggIndex);
        }
        if (op == null) {
            throw new IllegalArgumentException("HavingSpec op cannot be null");
        }
        if (Double.isNaN(threshold) || Double.isInfinite(threshold)) {
            throw new IllegalArgumentException("HavingSpec threshold 非有限: " + threshold);
        }
        this.aggIndex = aggIndex;
        this.op = op;
        this.threshold = threshold;
    }

    /**
     * 对给定聚合值判决是否满足本谓词。
     *
     * @param v 聚合值（非 null；取其 {@link AggValue#value} 与 {@link #threshold} 比较）
     * @return 满足 {@code true} / 不满足 {@code false}
     * @throws IllegalArgumentException {@code v == null}
     */
    public boolean test(AggValue v) {
        if (v == null) {
            throw new IllegalArgumentException("HavingSpec.test 参数 cannot be null");
        }
        switch (op) {
            case GT:
                return v.value > threshold;
            case GE:
                return v.value >= threshold;
            case LT:
                return v.value < threshold;
            case LE:
                return v.value <= threshold;
            case EQ:
                return v.value == threshold;
            case NE:
                return v.value != threshold;
            default:
                throw new IllegalStateException("未知 HavingOp: " + op);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HavingSpec)) {
            return false;
        }
        HavingSpec that = (HavingSpec) o;
        return aggIndex == that.aggIndex && op == that.op
                && Double.compare(threshold, that.threshold) == 0;
    }

    @Override
    public int hashCode() {
        int h = Integer.hashCode(aggIndex);
        h = 31 * h + op.hashCode();
        h = 31 * h + Double.hashCode(threshold);
        return h;
    }

    @Override
    public String toString() {
        return "aggs[" + aggIndex + "] " + symbol() + " " + threshold;
    }

    private String symbol() {
        switch (op) {
            case GT:
                return ">";
            case GE:
                return ">=";
            case LT:
                return "<";
            case LE:
                return "<=";
            case EQ:
                return "==";
            case NE:
                return "!=";
            default:
                return "?";
        }
    }
}
