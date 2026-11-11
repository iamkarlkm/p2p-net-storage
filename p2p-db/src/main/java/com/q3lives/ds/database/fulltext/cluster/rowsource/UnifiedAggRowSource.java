package com.q3lives.ds.database.fulltext.cluster.rowsource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.integration.GenericManager;

/**
 * UnifiedAggRowSource 统一聚合行源（P58：统一 FT/列存聚合行源边界）。
 *
 * <p>
 * P39 `GenericManagerRowSource`（纯 ORM）与 P45 `FulltextAggRowSource`（FT 原生）、P46
 * {@link ColumnarAggRowSource}（列存/服务端下推）各占一侧，其边界均以
 * `UnsupportedOperationException` 拒绝「对方的条件种类」：ORM 行源与列存行源都拒绝 FT 节点。本类把三者收敛为
 * <b>唯一</b> 行源边界： 一次 {@link #fetch} 内自行把 minterm 拆成 FT 节点与 ORM
 * 节点，再分派到对应通道，故上层再也遇不到 这两处 UOE。
 *
 * <p>
 * 三条路径（与 minterm 的 AND 语义一致）：
 * <ul>
 * <li><b>纯 ORM/列存</b>（无 FT 节点）：整体委托 {@code ormSource.fetch(minterm)}——列存下推或本地
 * `gm.findList` 语义完全由该行源决定；</li>
 * <li><b>纯 FT</b>（无 ORM 节点）：{@code ftClient.searchCluster} 求 docId，逐 id
 * {@code gm.getById} 物化；</li>
 * <li><b>FT + ORM/列存</b>：先取 FT 命中 docId 集合，再取 {@code ormSource.fetch(ormNodes)}
 * 的行， 按 `id ∈ ftDocIds` 过滤（AND 交集）。</li>
 * </ul>
 *
 * <p>
 * 多 FT 节点取 <b>交集（AND）</b>（对齐 P45 Q2）。结果严格 <b>按 docId 升序</b> 返回（确定性）。 强一致契约（对齐
 * P45/P46 R7）：纯 FT 路径命中 docId 若 {@code gm.getById} 返回 {@code null} → 抛
 * {@link IllegalStateException}，绝不静默丢弃。
 *
 * <p>
 * 无实例可变状态、0 新线程；不修改传入 {@link SearchOptions}（只读）。由于 {@link AggRowSource#fetch}
 * 不声明受检异常，FT 侧 {@link IOException} 统一包装为 {@link UncheckedIOException}（0 改接口签名）。
 *
 * <p>
 * 职责边界：本类 <b>不做</b> Join、不做分布式跨 shard 合并；`ormSource` 侧仍按其自身契约拒绝 未映射 op（如
 * `KW_LIKE`）。这两处 UOE 作为各子行源的内部不变量保留，仅不再对上层可见。
 *
 * @param <T> ORM 实体类型（DsTableAdapter 子类）
 * @author SP3.1 Implement Agent
 * @since P58 统一 FT/列存聚合行源边界
 */
public final class UnifiedAggRowSource<T extends DsTableAdapter> implements AggRowSource<T> {

    private final GenericManager<T> gm;
    private final DsFulltextClusterClient ftClient;
    private final AggRowSource<T> ormSource;
    private final int ftK;
    private final SearchOptions opt;

    /**
     * 构造统一聚合行源。
     *
     * @param gm 通用管理器（非 null；用于纯 FT 路径的 getById 物化）
     * @param ftClient 全文集群客户端（非 null）
     * @param ormSource ORM/列存侧行源（非 null；纯 ORM 与 FT+ORM 的 ORM 侧均委托给它）
     * @param ftK 每次 FT 检索候选上限（必须 ≥1）
     * @param opt SearchOptions（null → {@link SearchOptions#defaults()}）
     * @throws IllegalArgumentException 任一前置不满足
     */
    public UnifiedAggRowSource(GenericManager<T> gm, DsFulltextClusterClient ftClient,
            AggRowSource<T> ormSource, int ftK, SearchOptions opt) {
        if (gm == null) {
            throw new IllegalArgumentException("UnifiedAggRowSource gm cannot be null");
        }
        if (ftClient == null) {
            throw new IllegalArgumentException("UnifiedAggRowSource ftClient cannot be null");
        }
        if (ormSource == null) {
            throw new IllegalArgumentException("UnifiedAggRowSource ormSource cannot be null");
        }
        if (ftK < 1) {
            throw new IllegalArgumentException("ftK must >=1, got " + ftK);
        }
        this.gm = gm;
        this.ftClient = ftClient;
        this.ormSource = ormSource;
        this.ftK = ftK;
        this.opt = (opt == null) ? SearchOptions.defaults() : opt;
    }

    /**
     * 返回该 minterm 命中的所有行（按 docId 升序）。
     *
     * @param minterm AND 条件列表（非 null 非空，无 null 节点）
     * @return 命中行列表（非 null；按 docId 升序）
     * @throws IllegalArgumentException minterm 为 null / 空 / 含 null 节点
     * @throws IllegalStateException 纯 FT 命中 docId 无对应实体，或 ormSource.fetch 返回
     * null
     * @throws UncheckedIOException FT 侧 IO 异常（包装 {@link IOException}）
     */
    @Override
    public List<T> fetch(List<ConditionNode> minterm) {
        if (minterm == null || minterm.isEmpty()) {
            throw new IllegalArgumentException("minterm 不能为 null 或空（至少 1 个 ConditionNode）");
        }
        List<ConditionNode> ormNodes = new ArrayList<>();
        List<String> ftQueries = new ArrayList<>();
        int idx = 0;
        for (ConditionNode n : minterm) {
            if (n == null) {
                throw new IllegalArgumentException("minterm[" + idx + "] cannot be null");
            }
            if (ConditionNode.Kind.FT == n.kind) {
                ftQueries.add(n.ftQuery);
            } else {
                ormNodes.add(n);
            }
            idx++;
        }
        if (ftQueries.isEmpty()) {
            return byDocId(ormSource.fetch(minterm));
        }

        Set<Long> ftHits = ftHitIds(ftQueries);
        if (ormNodes.isEmpty()) {
            return byDocId(materialize(ftHits));
        }
        List<T> ormRows = ormSource.fetch(ormNodes);
        if (ormRows == null) {
            throw new IllegalStateException("UnifiedAggRowSource: ormSource.fetch 返回 null");
        }
        List<T> rows = new ArrayList<>();
        for (T r : ormRows) {
            if (r != null && ftHits.contains(r.getId())) {
                rows.add(r);
            }
        }
        return byDocId(rows);
    }

    /**
     * 多 FT 节点按 AND 取 docId 交集。
     *
     * @param ftQueries FT 查询串列表（非空）
     * @return FT 命中 docId 交集（无命中 → 空集）
     * @throws UncheckedIOException 任一 FT 检索 IO 异常
     */
    private Set<Long> ftHitIds(List<String> ftQueries) {
        Set<Long> acc = null;
        for (String q : ftQueries) {
            List<ScoredDocHit> hits;
            try {
                hits = ftClient.searchCluster(q, ftK, opt);
            } catch (IOException e) {
                throw new UncheckedIOException("FT searchCluster failed: FT(" + q + ")", e);
            }
            Set<Long> cur = new HashSet<>();
            if (hits != null) {
                for (ScoredDocHit h : hits) {
                    if (h != null) {
                        cur.add(h.docId);
                    }
                }
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
     * 逐 docId 物化实体（强一致：miss → ISE，不静默）。
     */
    private List<T> materialize(Set<Long> ids) {
        List<T> rows = new ArrayList<>(ids.size());
        for (Long id : ids) {
            T e = gm.getById(id);
            if (e == null) {
                throw new IllegalStateException("ORM entity missing for docId=" + id
                        + " (FT 命中但 ORM getById 返回 null，违反强一致)");
            }
            rows.add(e);
        }
        return rows;
    }

    /**
     * 按 docId（rowId）升序复制排序（确定性；不改动入参列表）。
     */
    private static <T extends DsTableAdapter> List<T> byDocId(List<T> rows) {
        List<T> out = (rows == null) ? new ArrayList<>() : new ArrayList<>(rows);
        out.sort(Comparator.comparingLong(r -> {
            Long v = r.getId();
            return v == null ? 0L : v;
        }));
        return out;
    }
}
