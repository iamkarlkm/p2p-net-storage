package com.q3lives.ds.database.fulltext.cluster.rowsource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.agg.GenericManagerRowSource;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.integration.GenericManager;

/**
 * FulltextAggRowSource FT 原生聚合行源（P45 OQ1：打通「FT 条件 + 聚合执行」）。
 *
 * <p>
 * 把一条 minterm（AND 条件列表）拆成 FT 侧（{@link ConditionNode.Kind#FT}）与 ORM 侧
 * （{@code Kind.ORM / IS_NULL / IS_NOT_NULL}），实现三条路径：
 * <ul>
 * <li><b>纯 ORM</b>：{@code gm.findList(toWrapper(minterm))}，与 {@link GenericManagerRowSource} 逐字段等价</li>
 * <li><b>纯 FT</b>：{@code ftClient.searchCluster(q, ftK, opt)} → docId → {@code gm.getById} 取实体</li>
 * <li><b>FT + ORM</b>：ORM 候选 ids（{@code gm.listIds}）∩ FT 命中 docIds → 取实体</li>
 * </ul>
 *
 * <p>
 * 多 FT 节点（同一 minterm 含 ≥2 个 FT 节点）取 <b>交集（AND）</b>，与 minterm 的 AND 语义一致。
 * 结果严格 <b>按 docId 升序</b>返回（确定性 R6）。
 *
 * <p>
 * 强一致契约（R7，对齐 {@code DsHybridQueryClient}）：FT 命中 docId 必须 {@code == entity.getId()}；
 * 交集命中但 {@code gm.getById(id) == null} → 抛 {@link IllegalStateException}，绝不静默丢弃。
 *
 * <p>
 * 无实例可变状态、0 新线程（R8）；不修改传入 {@link SearchOptions}（R9 只读）。由于
 * {@link AggRowSource#fetch} 不声明受检异常，FT 侧 {@link IOException} 统一包装为
 * {@link UncheckedIOException}（0 改接口签名）。
 *
 * @param <T> ORM 实体类型（DsTableAdapter 子类）
 * @author SP3.1 Implement Agent
 * @since P45 OQ1 FT 原生聚合行源
 */
public final class FulltextAggRowSource<T extends DsTableAdapter> implements AggRowSource<T> {

    private final GenericManager<T> gm;
    private final DsFulltextClusterClient ftClient;
    private final int ftK;
    private final SearchOptions opt;

    /**
     * 构造 FT 原生聚合行源。
     *
     * @param gm       通用管理器（非 null）
     * @param ftClient 全文集群客户端（非 null）
     * @param ftK      每次 FT 检索候选上限（必须 ≥1）
     * @param opt      SearchOptions（null → {@link SearchOptions#defaults()}）
     * @throws IllegalArgumentException gm / ftClient 为 null，或 ftK &lt; 1
     */
    public FulltextAggRowSource(GenericManager<T> gm, DsFulltextClusterClient ftClient,
            int ftK, SearchOptions opt) {
        if (gm == null) {
            throw new IllegalArgumentException("FulltextAggRowSource gm cannot be null");
        }
        if (ftClient == null) {
            throw new IllegalArgumentException("FulltextAggRowSource ftClient cannot be null");
        }
        if (ftK < 1) {
            throw new IllegalArgumentException("ftK must >=1, got " + ftK);
        }
        this.gm = gm;
        this.ftClient = ftClient;
        this.ftK = ftK;
        this.opt = (opt == null) ? SearchOptions.defaults() : opt;
    }

    /**
     * 返回该 minterm 命中的所有行（按 docId 升序）。
     *
     * @param minterm AND 条件列表（非 null 非空，无 null 节点）
     * @return 命中行列表（非 null；按 docId 升序）
     * @throws IllegalArgumentException minterm 为 null / 空 / 含 null 节点
     * @throws IllegalStateException    FT 交集命中但 docId 无对应 ORM 实体（违反强一致）
     * @throws UncheckedIOException     FT 侧 IO 异常（包装 {@link IOException}）
     */
    @Override
    public List<T> fetch(List<ConditionNode> minterm) {
        if (minterm == null) {
            throw new IllegalArgumentException("minterm cannot be null");
        }
        if (minterm.isEmpty()) {
            throw new IllegalArgumentException("minterm 不能为空（至少 1 个 ConditionNode）");
        }
        List<ConditionNode> ormNodes = new ArrayList<>();
        List<String> ftQueries = new ArrayList<>();
        for (int i = 0; i < minterm.size(); i++) {
            ConditionNode n = minterm.get(i);
            if (n == null) {
                throw new IllegalArgumentException("minterm[" + i + "] cannot be null");
            }
            if (n.kind == ConditionNode.Kind.FT) {
                ftQueries.add(n.ftQuery);
            } else {
                ormNodes.add(n);
            }
        }

        if (ftQueries.isEmpty()) {
            // 纯 ORM：复用 GenericManagerRowSource 的 minterm→QueryWrapper 映射（Q1=方案 A）
            return sortedByDocId(gm.findList(GenericManagerRowSource.toWrapper(minterm)));
        }

        Set<Long> ftDocIds = intersectFtDocIds(ftQueries);
        Set<Long> hitIds;
        if (ormNodes.isEmpty()) {
            hitIds = ftDocIds;
        } else {
            List<Long> ormIds = gm.listIds(GenericManagerRowSource.toWrapper(ormNodes));
            Set<Long> ormIdSet = new HashSet<>(ormIds);
            hitIds = new HashSet<>();
            for (Long id : ftDocIds) {
                if (ormIdSet.contains(id)) {
                    hitIds.add(id);
                }
            }
        }

        List<T> rows = new ArrayList<>(hitIds.size());
        for (Long id : hitIds) {
            T e = gm.getById(id);
            if (e == null) {
                throw new IllegalStateException("ORM entity missing for docId=" + id
                        + " (FT 交集命中但 ORM getById 返回 null，违反强一致)");
            }
            rows.add(e);
        }
        return sortedByDocId(rows);
    }

    /**
     * 多 FT 节点按 AND 取 docId 交集（Q2=交集）。
     *
     * @param ftQueries FT 查询串列表（非空）
     * @return FT 命中 docId 交集（无命中 → 空集）
     * @throws UncheckedIOException 任一 FT 检索 IO 异常
     */
    private Set<Long> intersectFtDocIds(List<String> ftQueries) {
        Set<Long> acc = null;
        for (String q : ftQueries) {
            Set<Long> cur = new HashSet<>();
            try {
                List<ScoredDocHit> hits = ftClient.searchCluster(q, ftK, opt);
                if (hits != null) {
                    for (ScoredDocHit h : hits) {
                        if (h != null) {
                            cur.add(h.docId);
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("FT searchCluster failed: FT(" + q + ")", e);
            }
            if (acc == null) {
                acc = cur;
            } else {
                acc.retainAll(cur);
            }
        }
        return (acc == null) ? new HashSet<>() : acc;
    }

    /**
     * 按 docId 升序复制排序（确定性 R6；不改动入参列表）。
     */
    private static <T extends DsTableAdapter> List<T> sortedByDocId(List<T> rows) {
        if (rows == null) {
            return new ArrayList<>();
        }
        List<T> out = new ArrayList<>(rows);
        out.sort((a, b) -> {
            Long ia = a.getId();
            Long ib = b.getId();
            return Long.compare(ia == null ? 0L : ia, ib == null ? 0L : ib);
        });
        return out;
    }
}
