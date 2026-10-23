package com.q3lives.ds.database.fulltext.cluster.agg;

import java.util.List;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;

/**
 * AggRowSource 行源抽象（P39 OQ1 DSL v3 聚合执行/合并引擎）。
 *
 * <p>
 * 把「给定一条 minterm（AND 条件列表），返回命中行」从具体存储解耦：{@code AggExecutor} 只依赖
 * 本接口，因此内存行集（单测）、ORM（{@link GenericManagerRowSource}）、业务自定义 FT 行源均可
 * 直接喂入执行引擎，且执行引擎 0 依赖具体存储实现。
 *
 * <p>
 * 契约：
 * <ul>
 * <li>返回 {@code null} → 调用方抛 {@link IllegalStateException}（显式 fail，不静默当空集）</li>
 * <li>同一 minterm 只被 fetch 一次（执行引擎按 minterm 顺序调用，行集复用于该 minterm 的所有聚合项）</li>
 * <li>返回的行必须带主键（{@code getId() != null}），否则 union 去重前提不可满足</li>
 * </ul>
 *
 * @param <T> ORM 实体类型
 * @author SP3.1 Implement Agent
 * @since P39 OQ1 DSL v3 聚合执行/合并引擎 MVP
 */
@FunctionalInterface
public interface AggRowSource<T extends DsTableAdapter> {

    /**
     * 返回该 minterm 命中的所有行。
     *
     * @param minterm AND 条件列表（非 null 非空；由 {@code plan.clause} 提供）
     * @return 命中行列表（非 null；返回 null 由调用方抛 ISE）
     */
    List<T> fetch(List<ConditionNode> minterm);
}
