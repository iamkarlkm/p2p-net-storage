package com.q3lives.ds.database.fulltext.cluster.agg;

import java.util.ArrayList;
import java.util.List;

import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;

/**
 * AggMerger 合并工具（P39 OQ1 DSL v3 聚合执行/合并引擎）：把多个部分结果合并成一个。
 *
 * <p>
 * 典型场景：各 shard 各自 {@code AggExecutor.execute} 得到部分 {@link AggResult}，业务方把它们交给
 * {@link #merge(List)} 合并出全局结果（MVP 边界：0 新 RPC / 0 新 handler，跨 shard 由业务方编排）。
 *
 * <p>
 * 合并契约（严格，R10）：仅接受 <b>agg 序列逐项（{@code AggSpec}）相等</b> 的部分结果，否则
 * {@link IllegalArgumentException}（绝不静默错配 / 错位相加）。逐聚合语义：
 * <ul>
 * <li>{@code COUNT}：{@code Σcount}，{@code value = Σcount}</li>
 * <li>{@code SUM}：{@code Σsum}，{@code value = Σsum}</li>
 * <li>{@code AVG}：{@code Σsum / Σcount}（{@code Σcount == 0 → 0.0}）—— 用 Σsum/Σcount 加权，
 * 而非对各部分 AVG 再求平均（避免权重错误）</li>
 * <li>{@code MIN}：取各部分极小值（空部分不参与，忽略其 0.0 哨兵）</li>
 * <li>{@code MAX}：取各部分极大值（同上）</li>
 * </ul>
 * {@code unionRowCount = Σ partials.unionRowCount}（同 agg 序列 → 同顺序 → 逐位合并）。
 *
 * @author SP3.1 Implement Agent
 * @since P39 OQ1 DSL v3 聚合执行/合并引擎 MVP
 */
public final class AggMerger {

    private AggMerger() {
    }

    /**
     * 合并多个部分结果为全局结果。
     *
     * @param partials 部分结果列表（非 null 非空且不含 null；各 agg 序列逐项相等）
     * @return 合并后的 AggResult
     * @throws IllegalArgumentException partials 为 null / 空 / 含 null / agg 序列不一致
     */
    public static AggResult merge(List<AggResult> partials) {
        if (partials == null) {
            throw new IllegalArgumentException("AggMerger partials cannot be null");
        }
        if (partials.isEmpty()) {
            throw new IllegalArgumentException("AggMerger partials 不能为空");
        }
        for (int i = 0; i < partials.size(); i++) {
            if (partials.get(i) == null) {
                throw new IllegalArgumentException("AggMerger partials[" + i + "] cannot be null");
            }
        }
        AggResult first = partials.get(0);
        int n = first.size();
        for (int i = 1; i < partials.size(); i++) {
            AggResult p = partials.get(i);
            if (p.size() != n) {
                throw new IllegalArgumentException("AggMerger agg 序列长度不一致: " + n + " vs " + p.size());
            }
            for (int j = 0; j < n; j++) {
                if (!p.value(j).spec.equals(first.value(j).spec)) {
                    throw new IllegalArgumentException("AggMerger agg 序列不一致 @[" + j + "]: "
                            + first.value(j).spec + " vs " + p.value(j).spec);
                }
            }
        }

        long rows = 0L;
        for (AggResult p : partials) {
            rows += p.unionRowCount;
        }
        List<AggValue> out = new ArrayList<>(n);
        for (int j = 0; j < n; j++) {
            AggValue acc = first.value(j);
            for (int i = 1; i < partials.size(); i++) {
                acc = mergeValues(acc, partials.get(i).value(j));
            }
            out.add(acc);
        }
        return new AggResult(out, rows);
    }

    /**
     * 按聚合语义合并两个同 spec 的 {@link AggValue}。
     *
     * @param a 第一个（非 null）
     * @param b 第二个（非 null）
     * @return 合并后的 AggValue
     * @throws IllegalArgumentException 任一为 null，或 {@code spec} 不相等
     */
    public static AggValue mergeValues(AggValue a, AggValue b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("AggMerger mergeValues 参数不能为 null");
        }
        if (!a.spec.equals(b.spec)) {
            throw new IllegalArgumentException("AggMerger spec 不一致: " + a.spec + " vs " + b.spec);
        }
        long count = a.count + b.count;
        double sum = a.sum + b.sum;
        double value;
        switch (a.spec.fn) {
            case COUNT:
                value = (double) count;
                break;
            case SUM:
                value = sum;
                break;
            case AVG:
                value = count == 0 ? 0.0 : sum / count;
                break;
            case MIN:
                value = extremum(a, b, true);
                break;
            case MAX:
                value = extremum(a, b, false);
                break;
            default:
                throw new IllegalArgumentException("未知 AggFn: " + a.spec.fn);
        }
        return new AggValue(a.spec, count, a.spec.fn == AggFn.COUNT ? 0.0 : sum, value);
    }

    /**
     * MIN/MAX 极值（空部分不参与，避免其 0.0 哨兵污染）。
     */
    private static double extremum(AggValue a, AggValue b, boolean min) {
        if (a.count == 0) {
            return b.value;
        }
        if (b.count == 0) {
            return a.value;
        }
        return min ? Math.min(a.value, b.value) : Math.max(a.value, b.value);
    }
}
