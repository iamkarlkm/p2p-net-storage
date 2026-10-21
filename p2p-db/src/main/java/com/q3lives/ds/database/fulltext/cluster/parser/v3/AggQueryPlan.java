package com.q3lives.ds.database.fulltext.cluster.parser.v3;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;

/**
 * AggQueryPlan 不可变结构（P38 OQ1 DSL v3 聚合函数 MVP）：编译期聚合执行计划。
 *
 * <p>
 * 计划 = {@code DnfClause}（OR of AND 的 minterm 集合）+ {@code List<AggSpec>}（聚合项，按声明序，
 * 可为空）。{@link #tasks()} 把二者做 <b>笛卡尔积</b> 展开为 N = {@code clause.size() × aggs.size()}
 * 个 {@link MintermAggTask}，顺序固定 = minterm 序（复用 P34 {@code DnfClause} 既有排序）×
 * agg 声明序（R9 确定性）。
 *
 * <p>
 * 无 {@code AGG} 子句时 {@code aggs} 为空列表，{@link #taskCount()} = 0，计划退化为纯 DNF
 * 表达式计划（仍合法）。业务方对每个 task 执行「单 minterm ∧ 聚合」，拿 N 个结果后自行合并
 * （合并属业务方，MVP 边界）。
 *
 * <p>
 * 5 IAE 守卫（构造即校验，构造后不可变）：
 * <ol>
 * <li>{@code clause == null} → IAE</li>
 * <li>{@code aggs == null} → IAE</li>
 * <li>{@code aggs} 含 null → IAE</li>
 * <li>{@code aggs.size() > MAX_AGG(8)} → IAE</li>
 * <li>构造后 {@code aggs} / {@link #tasks()} 均 {@code unmodifiable}</li>
 * </ol>
 *
 * <p>
 * {@link #fingerprint()} 输出 64-char hex SHA-256（{@code clause.fingerprint()} + agg 序列），
 * 跨 run / 跨进程稳定；{@link #deepEquals(AggQueryPlan, AggQueryPlan)} 做 clause + aggs 逐项全等。
 *
 * @author SP3.1 Implement Agent
 * @since P38 OQ1 DSL v3 聚合函数 MVP
 */
public final class AggQueryPlan {

    /** 单个计划允许的最大聚合项数（R7 硬上限）。 */
    public static final int MAX_AGG = 8;

    /** DNF 表达式计划（非 null）。 */
    public final DnfClause clause;

    /** 聚合项列表（不可变，按声明序，可为空）。 */
    public final List<AggSpec> aggs;

    /** 惰性缓存的任务列表（首次 tasks() 构建后固定）。 */
    private List<MintermAggTask> tasksCache;

    /**
     * 构造 AggQueryPlan 不可变实例（5 IAE 全检）。
     *
     * @param clause 非 null 的 DNF 表达式计划
     * @param aggs 非 null 且不含 null、size ≤ {@link #MAX_AGG} 的聚合项列表（可为空）
     * @throws IllegalArgumentException 任一 IAE 触发
     */
    public AggQueryPlan(DnfClause clause, List<AggSpec> aggs) {
        if (clause == null) {
            throw new IllegalArgumentException("AggQueryPlan clause cannot be null");
        }
        if (aggs == null) {
            throw new IllegalArgumentException("AggQueryPlan aggs cannot be null");
        }
        for (int i = 0; i < aggs.size(); i++) {
            if (aggs.get(i) == null) {
                throw new IllegalArgumentException("AggQueryPlan aggs[" + i + "] cannot be null");
            }
        }
        if (aggs.size() > MAX_AGG) {
            throw new IllegalArgumentException("AggQueryPlan aggs.size=" + aggs.size()
                    + " 超过 MAX_AGG=" + MAX_AGG);
        }
        this.clause = clause;
        this.aggs = Collections.unmodifiableList(new ArrayList<>(aggs));
    }

    /**
     * 展开为 N 个 {@code (minterm, aggFn)} 执行对（顺序 = minterm 序 × agg 声明序）。
     *
     * @return 不可变任务列表（aggs 为空时为 empty list）
     */
    public List<MintermAggTask> tasks() {
        List<MintermAggTask> cached = this.tasksCache;
        if (cached == null) {
            List<MintermAggTask> out = new ArrayList<>(clause.size() * aggs.size());
            for (int i = 0; i < clause.size(); i++) {
                List<ConditionNode> minterm = clause.getMiniTerm(i);
                for (AggSpec a : aggs) {
                    out.add(new MintermAggTask(i, minterm, a));
                }
            }
            cached = Collections.unmodifiableList(out);
            this.tasksCache = cached;
        }
        return cached;
    }

    /**
     * 任务总数 = {@code clause.size() × aggs.size()}。
     *
     * @return N（无聚合项时为 0）
     */
    public int taskCount() {
        return clause.size() * aggs.size();
    }

    /**
     * 64-char hex SHA-256 指纹（{@code clause.fingerprint()} 字节 + agg 序列 code/column），跨
     * run / 跨进程稳定。
     *
     * @return 64-char hex 指纹
     */
    public String fingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(clause.fingerprint().getBytes(StandardCharsets.UTF_8));
            for (AggSpec a : aggs) {
                md.update((byte) a.fn.code());
                md.update((byte) ':');
                md.update(a.column.getBytes(StandardCharsets.UTF_8));
                md.update((byte) ';');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 深度比对两个 AggQueryPlan 全等（clause 逐 minterm 节点全等 + aggs 逐项全等）。
     *
     * @param a 第一个（允许 null）
     * @param b 第二个（允许 null）
     * @return 100% 全等 true
     */
    public static boolean deepEquals(AggQueryPlan a, AggQueryPlan b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return DnfClause.deepEquals(a.clause, b.clause) && a.aggs.equals(b.aggs);
    }

    @Override
    public String toString() {
        return "AggQueryPlan[" + clause + " AGG" + aggs + "]";
    }
}
