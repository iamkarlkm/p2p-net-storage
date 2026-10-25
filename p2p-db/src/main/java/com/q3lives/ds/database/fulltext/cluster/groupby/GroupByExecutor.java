package com.q3lives.ds.database.fulltext.cluster.groupby;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.agg.AggValue;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;

/**
 * GroupByExecutor 分组执行引擎（P41 OQ1 DSL v3 聚合 v2 —— GROUP BY / HAVING / DISTINCT）。
 *
 * <p>
 * 在 P39 {@code AggExecutor}（全局单行聚合）的 union 去重框架之上追加 <b>分组</b> 能力，执行流程
 * （单进程，无状态，每次 execute 全局部变量，不承诺线程安全）：
 * <ol>
 * <li>逐 minterm（{@code spec.plan.clause} 顺序，固定）调 {@link AggRowSource#fetch} 取行</li>
 * <li>按主键 {@code getId()} 用 {@code HashSet<Long>} <b>跨 minterm 全局去重</b>（union 语义，R7；
 * DNF minterm 允许重叠）</li>
 * <li>对去重后的每一行按 {@code groupColumns} 规范化分组键（NULL → {@code "\u0000"} 哨兵，R8），
 * 落入 {@code HashMap<List<String>, Accum>} 分桶</li>
 * <li>对每个 agg 项取列值（{@link ToDoubleFunction}），{@code NaN} 视为 NULL 忽略（R8）：
 * 普通聚合累加 {@code count/sum/min/max}；{@code distinctAggIndexes} 标记项改累加
 * {@code HashSet<Double>}（仅 {@code COUNT} 合法，构造期已校验）</li>
 * <li>收尾算桶内 {@link AggValue}，按 {@code havings}（多谓词 <b>AND</b>）过滤，不满足的桶整桶剔除</li>
 * <li>桶序按分组键 {@code List<String>} <b>字典序</b>（逐元素 {@code String.compareTo}，R9）</li>
 * </ol>
 *
 * <p>
 * 列值获取契约：{@code aggColumnExtractors.get(col).applyAsDouble(row)} —— 行中该列为 NULL 时必须
 * 返回 {@link Double#NaN}（{@code ToDoubleFunction} 无法返回引用类型，NaN 是 NULL 哨兵）。分组列用
 * {@code groupColumnExtractors.get(col).apply(row)}，返回 {@code null} 即 NULL 分组列（合法成键，R8）。
 *
 * <p>
 * 6 IAE：{@code spec == null} / {@code source == null} / {@code groupColumnExtractors == null} /
 * {@code aggColumnExtractors == null} / 某分组列无 extractor / 某聚合列无 extractor。
 * <br>2 ISE：{@code source.fetch} 返回 null / 行 {@code getId() == null}（去重前提不可满足）。
 * <br>{@code plan.aggs} 为空合法：退化为 {@code SELECT DISTINCT groupColumns}（每桶 {@code values} 空）。
 *
 * @param <T> ORM 实体类型
 * @author SP3.1 Implement Agent
 * @since P41 OQ1 DSL v3 聚合 v2 MVP
 */
public final class GroupByExecutor<T extends DsTableAdapter> {

    /** 分组列为 NULL 时的 canonical 哨兵（独立成键，R8）。 */
    static final String NULL_KEY = "\u0000";

    /**
     * 执行分组计划。
     *
     * @param spec 分组规格（非 null）
     * @param source 行源（非 null）
     * @param groupColumnExtractors 分组列名 → 取值函数（非 null；返回 {@code null} 表示 NULL）
     * @param aggColumnExtractors 聚合列名 → double 取值函数（非 null；NULL 用 NaN 表示）
     * @return GroupByResult（桶序 = 分组键字典序；totalRowCount = union 去重后总行数）
     * @throws IllegalArgumentException 任一入参为 null，或某分组列 / 聚合列无 extractor
     * @throws IllegalStateException source.fetch 返回 null，或行 getId() == null
     */
    public GroupByResult execute(GroupBySpec spec, AggRowSource<T> source,
            Map<String, Function<T, String>> groupColumnExtractors,
            Map<String, ToDoubleFunction<T>> aggColumnExtractors) {
        if (spec == null) {
            throw new IllegalArgumentException("GroupByExecutor spec cannot be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("GroupByExecutor source cannot be null");
        }
        if (groupColumnExtractors == null) {
            throw new IllegalArgumentException("GroupByExecutor groupColumnExtractors cannot be null");
        }
        if (aggColumnExtractors == null) {
            throw new IllegalArgumentException("GroupByExecutor aggColumnExtractors cannot be null");
        }

        int gc = spec.groupColumns.size();
        List<Function<T, String>> gEx = new ArrayList<>(gc);
        for (int c = 0; c < gc; c++) {
            String col = spec.groupColumns.get(c);
            Function<T, String> ex = groupColumnExtractors.get(col);
            if (ex == null) {
                throw new IllegalArgumentException("分组列无 extractor: " + col);
            }
            gEx.add(ex);
        }

        List<AggSpec> aggs = spec.plan.aggs;
        int n = aggs.size();
        List<ToDoubleFunction<T>> aEx = new ArrayList<>(n);
        for (int j = 0; j < n; j++) {
            String col = aggs.get(j).column;
            ToDoubleFunction<T> ex = aggColumnExtractors.get(col);
            if (ex == null) {
                throw new IllegalArgumentException("聚合列无 extractor: " + col);
            }
            aEx.add(ex);
        }

        Set<Long> seen = new HashSet<>();
        Map<List<String>, Accum> buckets = new HashMap<>();
        DnfClause clause = spec.plan.clause;
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
                List<String> key = new ArrayList<>(gc);
                for (int c = 0; c < gc; c++) {
                    String v = gEx.get(c).apply(row);
                    key.add(v == null ? NULL_KEY : v);
                }
                Accum acc = buckets.get(key);
                if (acc == null) {
                    acc = new Accum(n, spec.distinctAggIndexes);
                    buckets.put(key, acc);
                }
                acc.rowCount++;
                for (int j = 0; j < n; j++) {
                    double x = aEx.get(j).applyAsDouble(row);
                    if (Double.isNaN(x)) {
                        continue;
                    }
                    acc.accumulate(j, x);
                }
            }
        }

        List<GroupBucket> out = new ArrayList<>(buckets.size());
        for (Map.Entry<List<String>, Accum> e : buckets.entrySet()) {
            List<AggValue> values = buildValues(aggs, e.getValue());
            if (passHaving(spec.havings, values)) {
                out.add(new GroupBucket(e.getKey(), values, e.getValue().rowCount));
            }
        }
        out.sort(GroupByExecutor::compareKey);
        return new GroupByResult(out, seen.size());
    }

    /**
     * 收尾：把桶累加器转成有序 {@link AggValue} 列表（顺序 = aggs 声明序）。
     */
    private static List<AggValue> buildValues(List<AggSpec> aggs, Accum acc) {
        int n = aggs.size();
        List<AggValue> values = new ArrayList<>(n);
        for (int j = 0; j < n; j++) {
            AggSpec s = aggs.get(j);
            Set<Double> distinct = acc.distinct[j];
            if (distinct != null) {
                long c = distinct.size();
                values.add(new AggValue(s, c, 0.0, (double) c));
                continue;
            }
            long c = acc.counts[j];
            double sm = s.fn == AggFn.COUNT ? 0.0 : acc.sums[j];
            double val;
            switch (s.fn) {
                case COUNT:
                    val = (double) c;
                    break;
                case SUM:
                    val = sm;
                    break;
                case AVG:
                    val = c == 0 ? 0.0 : acc.sums[j] / c;
                    break;
                case MIN:
                    val = c == 0 ? 0.0 : acc.mins[j];
                    break;
                case MAX:
                    val = c == 0 ? 0.0 : acc.maxs[j];
                    break;
                default:
                    throw new IllegalStateException("未知 AggFn: " + s.fn);
            }
            values.add(new AggValue(s, c, sm, val));
        }
        return values;
    }

    /**
     * HAVING 多谓词 AND 判决。
     */
    private static boolean passHaving(List<HavingSpec> havings, List<AggValue> values) {
        for (HavingSpec h : havings) {
            if (!h.test(values.get(h.aggIndex))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 分组键逐元素字典序比较。
     */
    private static int compareKey(GroupBucket a, GroupBucket b) {
        List<String> ka = a.key;
        List<String> kb = b.key;
        for (int i = 0; i < ka.size(); i++) {
            int c = ka.get(i).compareTo(kb.get(i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /**
     * 单桶单遍累加器（桶内 count/sum/min/max + COUNT(DISTINCT) 去重集合）。
     */
    private static final class Accum {

        final long[] counts;
        final double[] sums;
        final double[] mins;
        final double[] maxs;
        final Set<Double>[] distinct;
        long rowCount;

        @SuppressWarnings("unchecked")
        Accum(int n, List<Integer> distinctAggIndexes) {
            this.counts = new long[n];
            this.sums = new double[n];
            this.mins = new double[n];
            this.maxs = new double[n];
            this.distinct = new Set[n];
            for (Integer idx : distinctAggIndexes) {
                this.distinct[idx] = new HashSet<>();
            }
        }

        void accumulate(int j, double x) {
            if (distinct[j] != null) {
                distinct[j].add(x);
                return;
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
