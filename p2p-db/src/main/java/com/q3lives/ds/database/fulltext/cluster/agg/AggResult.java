package com.q3lives.ds.database.fulltext.cluster.agg;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * AggResult 不可变结构（P39 OQ1 DSL v3 聚合执行/合并引擎）：一次执行的完整结果。
 *
 * <p>
 * 结果 = 有序的 {@link AggValue} 列表（顺序严格 = {@code AggQueryPlan.aggs} 声明序，R9 确定性）
 * + union 去重后的行数 {@link #unionRowCount}。
 *
 * <p>
 * {@link #fingerprint()} 输出 64-char hex SHA-256（逐 {@code AggValue} 编码
 * {@code fn.code : column ; count ; sumBits ; valueBits}），跨 run / <b>跨进程</b> 稳定；
 * {@link #deepEquals(AggResult, AggResult)} 做 values 逐项全等 + unionRowCount 全等。
 *
 * <p>
 * 3 IAE 守卫（构造即校验，构造后不可变）：
 * <ol>
 * <li>{@code values == null} → IAE</li>
 * <li>{@code values} 含 null → IAE</li>
 * <li>{@code unionRowCount < 0} → IAE</li>
 * </ol>
 *
 * @author SP3.1 Implement Agent
 * @since P39 OQ1 DSL v3 聚合执行/合并引擎 MVP
 */
public final class AggResult {

    /** 聚合值列表（不可变，顺序 = aggs 声明序，可为空）。 */
    public final List<AggValue> values;

    /** union 去重后的行数（≥ 0）。 */
    public final long unionRowCount;

    /**
     * 构造 AggResult 不可变实例（3 IAE 全检）。
     *
     * @param values 非 null 且不含 null 的聚合值列表（可为空）
     * @param unionRowCount union 去重后的行数（≥ 0）
     * @throws IllegalArgumentException 任一 IAE 触发
     */
    public AggResult(List<AggValue> values, long unionRowCount) {
        if (values == null) {
            throw new IllegalArgumentException("AggResult values cannot be null");
        }
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == null) {
                throw new IllegalArgumentException("AggResult values[" + i + "] cannot be null");
            }
        }
        if (unionRowCount < 0) {
            throw new IllegalArgumentException("AggResult unionRowCount 必须 ≥ 0: " + unionRowCount);
        }
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
        this.unionRowCount = unionRowCount;
    }

    /**
     * 聚合项个数。
     *
     * @return {@code values.size()}
     */
    public int size() {
        return values.size();
    }

    /**
     * 取第 {@code i} 个聚合值。
     *
     * @param i 0-based 下标
     * @return 第 i 个 AggValue
     * @throws IndexOutOfBoundsException 越界
     */
    public AggValue value(int i) {
        return values.get(i);
    }

    /**
     * 64-char hex SHA-256 指纹（逐 AggValue 编码 {@code fn.code : column ; count ; sumBits ;
     * valueBits}），跨 run / 跨进程稳定。
     *
     * @return 64-char hex 指纹
     */
    public String fingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (AggValue v : values) {
                md.update((byte) v.spec.fn.code());
                md.update((byte) ':');
                md.update(v.spec.column.getBytes(StandardCharsets.UTF_8));
                md.update((byte) ';');
                md.update(Long.toString(v.count).getBytes(StandardCharsets.UTF_8));
                md.update((byte) ';');
                md.update(Long.toString(Double.doubleToLongBits(v.sum)).getBytes(StandardCharsets.UTF_8));
                md.update((byte) ';');
                md.update(Long.toString(Double.doubleToLongBits(v.value)).getBytes(StandardCharsets.UTF_8));
                md.update((byte) ';');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 深度比对两个 AggResult 全等（values 逐项全等 + unionRowCount 全等）。
     *
     * @param a 第一个（允许 null）
     * @param b 第二个（允许 null）
     * @return 100% 全等 true
     */
    public static boolean deepEquals(AggResult a, AggResult b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.unionRowCount == b.unionRowCount && a.values.equals(b.values);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AggResult)) {
            return false;
        }
        return deepEquals(this, (AggResult) o);
    }

    @Override
    public int hashCode() {
        return 31 * values.hashCode() + Long.hashCode(unionRowCount);
    }

    @Override
    public String toString() {
        return "AggResult" + values + " rows=" + unionRowCount;
    }
}
