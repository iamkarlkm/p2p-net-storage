package com.q3lives.ds.database.fulltext.cluster.agg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;

/**
 * AggExecutor 执行引擎（P39 OQ1 DSL v3 聚合执行/合并引擎）：union 去重 + 单遍 5 聚合。
 *
 * <p>
 * 执行流程（单进程，无状态，每次 execute 全局部变量，不承诺线程安全）：
 * <ol>
 * <li>逐 minterm（{@code plan.clause} 顺序，固定）调 {@link AggRowSource#fetch} 取行</li>
 * <li>按主键 {@code getId()} 用 {@code HashSet<Long>} <b>跨 minterm 全局去重</b>（union 语义，R7；
 * DNF minterm 允许重叠）</li>
 * <li>对去重后的每一行、每个 agg 项取列值（{@link ToDoubleFunction}），{@code NaN} 视为
 * NULL 并忽略（SQL 语义，R8），否则累加 {@code count/sum/min/max}</li>
 * </ol>
 *
 * <p>
 * 列值获取契约：{@code columnExtractors.get(col).applyAsDouble(row)} —— 行中该列为 NULL 时必须
 * 返回 {@link Double#NaN}（{@code ToDoubleFunction} 无法返回引用类型，NaN 是 NULL 哨兵）。输出
 * {@link AggResult#values} 顺序严格 = {@code plan.aggs} 声明序（R9）。
 *
 * <p>
 * 4 IAE：{@code plan == null} / {@code source == null} / {@code columnExtractors == null} /
 * 某 {@code AggSpec.column} 在 map 中无 extractor。
 * <br>2 ISE：{@code source.fetch} 返回 null / 行 {@code getId() == null}（去重前提不可满足）。
 * <br>短路：{@code plan.aggs} 为空 → 直接返回 {@code [values=空, unionRowCount=0]}，0 次 fetch。
 *
 * @param <T> ORM 实体类型
 * @author SP3.1 Implement Agent
 * @since P39 OQ1 DSL v3 聚合执行/合并引擎 MVP
 */
public final class AggExecutor<T extends DsTableAdapter> {

    /**
     * 执行聚合计划：对每个 minterm 取行、跨 minterm 按主键去重、单遍算出各聚合项。
     *
     * @param plan 聚合计划（非 null）
     * @param source 行源（非 null）
     * @param columnExtractors 列名 → 取值函数（非 null；NULL 用 NaN 表示）
     * @return AggResult（values 顺序 = plan.aggs 声明序）
     * @throws IllegalArgumentException plan/source/columnExtractors 为 null，或某聚合列无 extractor
     * @throws IllegalStateException source.fetch 返回 null，或行 getId() == null
     */
    public AggResult execute(AggQueryPlan plan, AggRowSource<T> source,
            Map<String, ToDoubleFunction<T>> columnExtractors) {
        if (plan == null) {
            throw new IllegalArgumentException("AggExecutor plan cannot be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("AggExecutor source cannot be null");
        }
        if (columnExtractors == null) {
            throw new IllegalArgumentException("AggExecutor columnExtractors cannot be null");
        }
        List<AggSpec> aggs = plan.aggs;
        int n = aggs.size();
        List<ToDoubleFunction<T>> extractors = new ArrayList<>(n);
        for (int j = 0; j < n; j++) {
            String col = aggs.get(j).column;
            ToDoubleFunction<T> ex = columnExtractors.get(col);
            if (ex == null) {
                throw new IllegalArgumentException("聚合列无 extractor: " + col);
            }
            extractors.add(ex);
        }
        if (n == 0) {
            return new AggResult(Collections.emptyList(), 0);
        }

        long[] counts = new long[n];
        double[] sums = new double[n];
        double[] mins = new double[n];
        double[] maxs = new double[n];
        Set<Long> seen = new HashSet<>();
        DnfClause clause = plan.clause;
        for (int i = 0; i < clause.size(); i++) {
            List<ConditionNode> minterm = clause.getMiniTerm(i);
            List<T> rows = source.fetch(minterm);
            if (rows == null) {
                throw new IllegalStateException("AggRowSource.fetch 返回 null (mintermIndex=" + i + ")");
            }
            for (T row : rows) {
                Long pk = row.getId();
                if (pk == null) {
                    throw new IllegalStateException(
                            "行 getId() 为 null，无法 union 去重 (mintermIndex=" + i + ")");
                }
                if (!seen.add(pk)) {
                    continue;
                }
                for (int j = 0; j < n; j++) {
                    double x = extractors.get(j).applyAsDouble(row);
                    if (Double.isNaN(x)) {
                        continue;
                    }
                    if (counts[j] == 0) {
                        mins[j] = x;
                        maxs[j] = x;
                    } else {
                        if (x < mins[j]) {
                            mins[j] = x;
                        }
                        if (x > maxs[j]) {
                            maxs[j] = x;
                        }
                    }
                    counts[j]++;
                    sums[j] += x;
                }
            }
        }

        List<AggValue> values = new ArrayList<>(n);
        for (int j = 0; j < n; j++) {
            AggSpec s = aggs.get(j);
            long c = counts[j];
            double sm = s.fn == AggFn.COUNT ? 0.0 : sums[j];
            double val;
            switch (s.fn) {
                case COUNT:
                    val = (double) c;
                    break;
                case SUM:
                    val = sm;
                    break;
                case AVG:
                    val = c == 0 ? 0.0 : sums[j] / c;
                    break;
                case MIN:
                    val = c == 0 ? 0.0 : mins[j];
                    break;
                case MAX:
                    val = c == 0 ? 0.0 : maxs[j];
                    break;
                default:
                    throw new IllegalStateException("未知 AggFn: " + s.fn);
            }
            values.add(new AggValue(s, c, sm, val));
        }
        return new AggResult(values, seen.size());
    }
}
