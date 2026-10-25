package com.q3lives.ds.database.fulltext.cluster.groupby;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * GroupByResult 不可变结构（P41 OQ1 DSL v3 聚合 v2 —— GROUP BY / HAVING / DISTINCT）：一次分组结果。
 *
 * <p>
 * 结果 = 有序的 {@link GroupBucket} 列表（桶序严格 = 分组键 {@code List<String>} <b>字典序</b>，
 * R9 确定性）+ union 去重后的总行数。
 * <ul>
 * <li>{@link #buckets}：保留桶（已过 HAVING 过滤），桶序 = 键逐元素 {@code String.compareTo} 字典序</li>
 * <li>{@link #totalRowCount}：union 去重后的 <b>总行数</b>（HAVING 过滤前，{@code seen.size()}`，≥ 0）</li>
 * </ul>
 *
 * <p>
 * 2 IAE 守卫（构造即校验，构造后不可变）：
 * <ol>
 * <li>{@code buckets == null} 或含 {@code null} → IAE</li>
 * <li>{@code totalRowCount < 0} → IAE</li>
 * </ol>
 *
 * <p>
 * {@link #fingerprint()} 输出 64-char hex SHA-256（逐 {@code bucket.fingerprint()}），跨 run / 跨进程
 * 稳定；{@link #deepEquals(GroupByResult, GroupByResult)} 做 buckets 逐项全等 + totalRowCount 全等。
 *
 * @author SP3.1 Implement Agent
 * @since P41 OQ1 DSL v3 聚合 v2 MVP
 */
public final class GroupByResult {

    /** 分组桶列表（不可变，桶序 = 键字典序，可为空）。 */
    public final List<GroupBucket> buckets;

    /** union 去重后总行数（HAVING 过滤前，≥ 0）。 */
    public final long totalRowCount;

    /**
     * 构造 GroupByResult 不可变实例（2 IAE 全检）。
     *
     * @param buckets 非 null 且不含 null 的分组桶列表（可为空）
     * @param totalRowCount union 去重后总行数（≥ 0）
     * @throws IllegalArgumentException 任一 IAE 触发
     */
    public GroupByResult(List<GroupBucket> buckets, long totalRowCount) {
        if (buckets == null) {
            throw new IllegalArgumentException("GroupByResult buckets cannot be null");
        }
        for (int i = 0; i < buckets.size(); i++) {
            if (buckets.get(i) == null) {
                throw new IllegalArgumentException("GroupByResult buckets[" + i + "] cannot be null");
            }
        }
        if (totalRowCount < 0) {
            throw new IllegalArgumentException("GroupByResult totalRowCount 必须 ≥ 0: " + totalRowCount);
        }
        this.buckets = Collections.unmodifiableList(new ArrayList<>(buckets));
        this.totalRowCount = totalRowCount;
    }

    /**
     * 桶个数。
     *
     * @return {@code buckets.size()}
     */
    public int size() {
        return buckets.size();
    }

    /**
     * 取第 {@code i} 个桶。
     *
     * @param i 0-based 下标
     * @return 第 i 个 GroupBucket
     * @throws IndexOutOfBoundsException 越界
     */
    public GroupBucket bucket(int i) {
        return buckets.get(i);
    }

    /**
     * 64-char hex SHA-256 指纹（逐 {@code bucket.fingerprint()}），跨 run / 跨进程稳定。
     *
     * @return 64-char hex 指纹
     */
    public String fingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (GroupBucket b : buckets) {
                md.update(b.fingerprint().getBytes(StandardCharsets.UTF_8));
                md.update((byte) '|');
            }
            md.update(Long.toString(totalRowCount).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 深度比对两个 GroupByResult 全等（buckets 逐项全等 + totalRowCount 全等）。
     *
     * @param a 第一个（允许 null）
     * @param b 第二个（允许 null）
     * @return 100% 全等 true
     */
    public static boolean deepEquals(GroupByResult a, GroupByResult b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.totalRowCount == b.totalRowCount && a.buckets.equals(b.buckets);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GroupByResult)) {
            return false;
        }
        return deepEquals(this, (GroupByResult) o);
    }

    @Override
    public int hashCode() {
        return 31 * buckets.hashCode() + Long.hashCode(totalRowCount);
    }

    @Override
    public String toString() {
        return "GroupByResult" + buckets + " rows=" + totalRowCount;
    }
}
