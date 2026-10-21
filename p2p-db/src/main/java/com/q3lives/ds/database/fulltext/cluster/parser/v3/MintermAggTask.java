package com.q3lives.ds.database.fulltext.cluster.parser.v3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;

/**
 * MintermAggTask 不可变结构（P38 OQ1 DSL v3 聚合函数 MVP）：一个 {@code (minterm, aggFn)} 执行对。
 *
 * <p>
 * DSL 编译期拆分的原子执行单元：业务方对每个 {@code MintermAggTask} 执行一次
 * 「以 {@code minterm} 作为 WHERE 条件 + {@code aggSpec} 作为聚合」的查询，得到 N 个结果后
 * 自行合并（合并属业务方，MVP 边界）。{@code mintermIndex} 标识该 minterm 在
 * {@link com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause} 中的位置，供业务方
 * 回溯分组。
 *
 * <p>
 * 5 IAE 守卫（构造即校验，构造后不可变）：
 * <ol>
 * <li>{@code mintermIndex < 0} → IAE</li>
 * <li>{@code minterm == null} → IAE</li>
 * <li>{@code minterm.isEmpty()} → IAE（minterm 至少 1 个条件）</li>
 * <li>{@code minterm} 含 null 节点 → IAE</li>
 * <li>{@code aggSpec == null} → IAE</li>
 * </ol>
 *
 * <p>
 * {@code minterm} 构造时做防御性拷贝并 {@code unmodifiable} 包装，保证任务对象跨线程只读安全；
 * {@code equals/hashCode} 按 {@code (mintermIndex, minterm 逐节点, aggSpec)} 严格判定。
 *
 * @author SP3.1 Implement Agent
 * @since P38 OQ1 DSL v3 聚合函数 MVP
 */
public final class MintermAggTask {

    /** minterm 在 DnfClause 中的 0-based 下标（≥ 0）。 */
    public final int mintermIndex;

    /** minterm 条件列表（不可变，至少 1 个节点）。 */
    public final List<ConditionNode> minterm;

    /** 本任务对应的聚合项（非 null）。 */
    public final AggSpec aggSpec;

    /**
     * 构造 MintermAggTask 不可变实例（5 IAE 全检）。
     *
     * @param mintermIndex 0-based minterm 下标（≥ 0）
     * @param minterm 非 null 非空且不含 null 节点的条件列表
     * @param aggSpec 聚合项（非 null）
     * @throws IllegalArgumentException 任一 IAE 触发
     */
    public MintermAggTask(int mintermIndex, List<ConditionNode> minterm, AggSpec aggSpec) {
        if (mintermIndex < 0) {
            throw new IllegalArgumentException("mintermIndex 必须 ≥ 0: " + mintermIndex);
        }
        if (minterm == null) {
            throw new IllegalArgumentException("minterm cannot be null");
        }
        if (minterm.isEmpty()) {
            throw new IllegalArgumentException("minterm 不能为空（至少 1 个 ConditionNode）");
        }
        for (int i = 0; i < minterm.size(); i++) {
            if (minterm.get(i) == null) {
                throw new IllegalArgumentException("minterm[" + i + "] cannot be null");
            }
        }
        if (aggSpec == null) {
            throw new IllegalArgumentException("aggSpec cannot be null");
        }
        this.mintermIndex = mintermIndex;
        this.minterm = Collections.unmodifiableList(new ArrayList<>(minterm));
        this.aggSpec = aggSpec;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MintermAggTask)) {
            return false;
        }
        MintermAggTask that = (MintermAggTask) o;
        return mintermIndex == that.mintermIndex
                && minterm.equals(that.minterm)
                && aggSpec.equals(that.aggSpec);
    }

    @Override
    public int hashCode() {
        int h = mintermIndex;
        h = 31 * h + minterm.hashCode();
        h = 31 * h + Objects.hashCode(aggSpec);
        return h;
    }

    @Override
    public String toString() {
        return "MintermAggTask[" + mintermIndex + ", " + minterm + ", " + aggSpec + "]";
    }
}
