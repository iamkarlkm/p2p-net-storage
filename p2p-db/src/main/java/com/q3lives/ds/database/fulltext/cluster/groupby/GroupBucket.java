package com.q3lives.ds.database.fulltext.cluster.groupby;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import com.q3lives.ds.database.fulltext.cluster.agg.AggValue;

/**
 * GroupBucket 不可变结构（P41 OQ1 DSL v3 聚合 v2 —— GROUP BY / HAVING / DISTINCT）：单个分组桶。
 *
 * <p>
 * 一个桶 = 一组分组键 + 该组 union 去重后的聚合答案 + 组内行数：
 * <ul>
 * <li>{@link #key}：分组键，与 {@code GroupBySpec.groupColumns} <b>一一对应</b>；某分组列为 NULL 时
 * 以 canonical 哨兵 {@code "\u0000"} 占位（R8，独立成键）</li>
 * <li>{@link #values}：桶内聚合答案，顺序严格 = {@code plan.aggs} 声明序（{@code aggs} 为空时为空
 * 列表，即 {@code SELECT DISTINCT groupColumns}）</li>
 * <li>{@link #rowCount}：本桶 union 去重后的行数（≥ 0）</li>
 * </ul>
 *
 * <p>
 * 3 IAE 守卫（构造即校验，构造后不可变）：
 * <ol>
 * <li>{@code key == null} 或含 {@code null} → IAE</li>
 * <li>{@code values == null} 或含 {@code null} → IAE</li>
 * <li>{@code rowCount < 0} → IAE</li>
 * </ol>
 *
 * <p>
 * {@link #fingerprint()} 输出 64-char hex SHA-256（{@code key} + 逐 {@link AggValue} 同 P39 编码
 * {@code fn.code : column ; count ; sumBits ; valueBits}），跨 run / 跨进程稳定。
 *
 * @author SP3.1 Implement Agent
 * @since P41 OQ1 DSL v3 聚合 v2 MVP
 */
public final class GroupBucket {

    /** 分组键（不可变，与 groupColumns 一一对应；NULL → "\u0000"）。 */
    public final List<String> key;

    /** 桶内聚合答案（不可变，顺序 = aggs 声明序，可为空）。 */
    public final List<AggValue> values;

    /** 本桶 union 去重后的行数（≥ 0）。 */
    public final long rowCount;

    /**
     * 构造 GroupBucket 不可变实例（3 IAE 全检）。
     *
     * @param key 分组键（非 null 且不含 null）
     * @param values 聚合答案（非 null 且不含 null，可为空）
     * @param rowCount 本桶行数（≥ 0）
     * @throws IllegalArgumentException 任一 IAE 触发
     */
    public GroupBucket(List<String> key, List<AggValue> values, long rowCount) {
        if (key == null) {
            throw new IllegalArgumentException("GroupBucket key cannot be null");
        }
        for (int i = 0; i < key.size(); i++) {
            if (key.get(i) == null) {
                throw new IllegalArgumentException("GroupBucket key[" + i + "] cannot be null");
            }
        }
        if (values == null) {
            throw new IllegalArgumentException("GroupBucket values cannot be null");
        }
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == null) {
                throw new IllegalArgumentException("GroupBucket values[" + i + "] cannot be null");
            }
        }
        if (rowCount < 0) {
            throw new IllegalArgumentException("GroupBucket rowCount 必须 ≥ 0: " + rowCount);
        }
        this.key = Collections.unmodifiableList(new ArrayList<>(key));
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
        this.rowCount = rowCount;
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
     * 64-char hex SHA-256 指纹（{@code key} + 逐 AggValue 同 P39 编码），跨 run / 跨进程稳定。
     *
     * @return 64-char hex 指纹
     */
    public String fingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String k : key) {
                md.update(k.getBytes(StandardCharsets.UTF_8));
                md.update((byte) ',');
            }
            md.update((byte) '|');
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GroupBucket)) {
            return false;
        }
        GroupBucket that = (GroupBucket) o;
        return rowCount == that.rowCount && key.equals(that.key) && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        int h = key.hashCode();
        h = 31 * h + values.hashCode();
        h = 31 * h + Long.hashCode(rowCount);
        return h;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < key.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(key.get(i));
        }
        for (AggValue v : values) {
            sb.append(", ").append(v);
        }
        sb.append(", rows=").append(rowCount).append('}');
        return sb.toString();
    }
}
