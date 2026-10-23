package com.q3lives.ds.database.fulltext.cluster.agg;

import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;

/**
 * AggValue 不可变结构（P39 OQ1 DSL v3 聚合执行/合并引擎）：单个聚合项的执行结果。
 *
 * <p>
 * 一个 {@code AggValue} 承载「对某列做一次聚合」的答案 + 合并所需的两个中间量：
 * <ul>
 * <li>{@link #count}：参与聚合的 <b>非 NULL</b> 值个数（SQL 语义忽略 NULL，R8）</li>
 * <li>{@link #sum}：非 NULL 值之和（{@code COUNT} 恒 {@code 0.0}）</li>
 * <li>{@link #value}：聚合答案 —— {@code COUNT → count} / {@code SUM → sum} /
 * {@code AVG → sum / count}（{@code count == 0 → 0.0}）/ {@code MIN·MAX → 极值}</li>
 * </ul>
 *
 * <p>
 * 保留 {@code sum} 的目的是让 {@code AggMerger} 能以 {@code Σsum / Σcount} 精确合并
 * {@code AVG}（对部分 AVG 再求平均会导致权重错误，R10）。
 *
 * <p>
 * 4 IAE 守卫（构造即校验，构造后不可变）：
 * <ol>
 * <li>{@code spec == null} → IAE</li>
 * <li>{@code count < 0} → IAE</li>
 * <li>{@code sum} 为 {@code NaN} / {@code Infinite} → IAE</li>
 * <li>{@code value} 为 {@code NaN} / {@code Infinite} → IAE</li>
 * </ol>
 *
 * <p>
 * {@code toString()} 输出 {@code "COUNT(price)=12.0"} 形式，便于调试与日志。
 *
 * @author SP3.1 Implement Agent
 * @since P39 OQ1 DSL v3 聚合执行/合并引擎 MVP
 */
public final class AggValue {

    /** 本值对应的聚合项（非 null）。 */
    public final AggSpec spec;

    /** 参与聚合的非 NULL 值个数（≥ 0）。 */
    public final long count;

    /** 非 NULL 值之和（{@code COUNT} 恒 0.0；非 NaN / 非 Infinite）。 */
    public final double sum;

    /** 聚合答案（非 NaN / 非 Infinite）。 */
    public final double value;

    /**
     * 构造 AggValue 不可变实例（4 IAE 全检）。
     *
     * @param spec 聚合项（非 null）
     * @param count 参与聚合的非 NULL 值个数（≥ 0）
     * @param sum 非 NULL 值之和（非 NaN / 非 Infinite）
     * @param value 聚合答案（非 NaN / 非 Infinite）
     * @throws IllegalArgumentException 任一 IAE 触发
     */
    public AggValue(AggSpec spec, long count, double sum, double value) {
        if (spec == null) {
            throw new IllegalArgumentException("AggValue spec cannot be null");
        }
        if (count < 0) {
            throw new IllegalArgumentException("AggValue count 必须 ≥ 0: " + count);
        }
        if (Double.isNaN(sum) || Double.isInfinite(sum)) {
            throw new IllegalArgumentException("AggValue sum 非有限: " + sum);
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("AggValue value 非有限: " + value);
        }
        this.spec = spec;
        this.count = count;
        this.sum = sum;
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AggValue)) {
            return false;
        }
        AggValue that = (AggValue) o;
        return count == that.count && spec.equals(that.spec)
                && Double.compare(sum, that.sum) == 0
                && Double.compare(value, that.value) == 0;
    }

    @Override
    public int hashCode() {
        int h = spec.hashCode();
        h = 31 * h + Long.hashCode(count);
        h = 31 * h + Double.hashCode(sum);
        h = 31 * h + Double.hashCode(value);
        return h;
    }

    @Override
    public String toString() {
        return spec + "=" + value;
    }
}
