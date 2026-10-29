package com.q3lives.ds.database.fulltext.cluster.rowsource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.DsDatabaseClusterClient;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.agg.GenericManagerRowSource;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.integration.GenericManager;

/**
 * ColumnarAggRowSource 列存/服务端聚合行源（P46 OQ12-㉔①：把聚合行源下沉到列存/分布式客户端）。
 *
 * <p>
 * 承接 P45 拆分的另一半任务：P45 补齐了 FT 原生行源，本类补齐 <b>列存/服务端</b> 行源。把一条
 * minterm（AND 条件列表）经谓词下推交给列存集群客户端解析候选 id，再物化成 ORM 行，交由
 * {@code AggExecutor} / {@code GroupByExecutor}（0 改，仅依赖 {@link AggRowSource} 接口）执行。
 *
 * <p>
 * 双模（Q2）：
 * <ul>
 * <li><b>服务端下推</b>：{@code colClient != null} → minterm 映射为 {@link DbQuery}（谓词下推）→
 * {@link DsDatabaseClusterClient#queryIds(String, DbQuery, int, int)}（服务端分区路由 + id 下推）
 * → 逐 id {@link GenericManager#getById} 物化；</li>
 * <li><b>本地回退</b>：{@code colClient == null} → {@code gm.findList(toWrapper(minterm))}，与
 * {@link GenericManagerRowSource} 逐字段等价。</li>
 * </ul>
 *
 * <p>
 * 职责边界：纯 FT 节点 → {@link UnsupportedOperationException}（FT 由 P45
 * {@link FulltextAggRowSource} 承担）。强一致契约（R7）：列存命中的 id 若 {@code gm.getById} 返回
 * {@code null} → 抛 {@link IllegalStateException}，绝不静默丢弃。结果严格 <b>按 docId 升序</b> 返回
 * （确定性 R6）。
 *
 * <p>
 * 无实例可变状态、0 新线程（R8）；{@code fetch} 纯函数式。由于 {@link AggRowSource#fetch} 不声明受检
 * 异常，列存侧 {@link IOException} 统一包装为 {@link UncheckedIOException}（0 改接口签名）。
 *
 * @param <T> ORM 实体类型（DsTableAdapter 子类）
 * @author SP3.1 Implement Agent
 * @since P46 OQ12-㉔① 列存/服务端聚合下推
 */
public final class ColumnarAggRowSource<T extends DsTableAdapter> implements AggRowSource<T> {

    /** 单次下推候选上限（防 {@code queryIds} 内部 {@code limit + offset} 溢出）。 */
    private static final int MAX_ROWS = 1 << 24;

    private final GenericManager<T> gm;
    private final DsDatabaseClusterClient colClient;
    private final String entityClassName;

    /**
     * 构造列存/服务端聚合行源。
     *
     * @param gm              通用管理器（非 null；用于 getById 物化 + 双模回退）
     * @param colClient       列存/分布式客户端（可 null；null → 回退 gm.findList 本地路径）
     * @param entityClassName 实体类名（colClient != null 时必填非空）
     * @throws IllegalArgumentException gm 为 null，或 colClient != null 而 entityClassName 空白
     */
    public ColumnarAggRowSource(GenericManager<T> gm, DsDatabaseClusterClient colClient,
            String entityClassName) {
        if (gm == null) {
            throw new IllegalArgumentException("ColumnarAggRowSource gm cannot be null");
        }
        if (colClient != null && (entityClassName == null || entityClassName.isBlank())) {
            throw new IllegalArgumentException("entityClassName 必填且非 blank（colClient != null）");
        }
        this.gm = gm;
        this.colClient = colClient;
        this.entityClassName = entityClassName;
    }

    /**
     * 返回该 minterm 命中的所有行（按 docId 升序）。
     *
     * @param minterm AND 条件列表（非 null 非空，无 null 节点，无 FT 节点）
     * @return 命中行列表（非 null；按 docId 升序）
     * @throws IllegalArgumentException        minterm 为 null / 空 / 含 null 节点
     * @throws UnsupportedOperationException   含 FT 节点（FT 走 FulltextAggRowSource）
     * @throws IllegalStateException           列存命中 id 无对应实体（违反强一致）
     * @throws UncheckedIOException            colClient.queryIds 抛 IOException（包装，0 改接口签名）
     */
    @Override
    public List<T> fetch(List<ConditionNode> minterm) {
        if (colClient == null) {
            return orderByRowId(gm.findList(GenericManagerRowSource.toWrapper(minterm)));
        }
        DbQuery dbQuery = toDbQuery(minterm);
        List<Long> ids;
        try {
            ids = colClient.queryIds(entityClassName, dbQuery, 0, MAX_ROWS);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "ColumnarAggRowSource queryIds failed: entity=" + entityClassName, e);
        }
        if (ids == null) {
            throw new IllegalStateException("DsDatabaseClusterClient.queryIds 返回 null");
        }
        List<T> rows = new ArrayList<>(ids.size());
        for (Long id : ids) {
            T row = gm.getById(id);
            if (row == null) {
                throw new IllegalStateException("ORM entity missing for id=" + id
                        + "（列存 queryIds 命中但 getById 返回 null，违反强一致）");
            }
            rows.add(row);
        }
        return orderByRowId(rows);
    }

    /**
     * minterm → {@link DbQuery} 谓词下推映射（ORM op → {@link DbQueryOp}）。
     *
     * @param minterm AND 条件列表（非 null 非空，无 null 节点，无 FT 节点）
     * @return DbQuery（where 按 minterm 顺序逐条映射）
     * @throws IllegalArgumentException        minterm 为 null / 空 / 含 null 节点
     * @throws UnsupportedOperationException   含 FT 节点，或 ORM op 不在映射集内
     */
    public static DbQuery toDbQuery(List<ConditionNode> minterm) {
        if (minterm == null || minterm.isEmpty()) {
            throw new IllegalArgumentException("minterm 不能为 null 或空（至少 1 个 ConditionNode）");
        }
        DbQuery q = new DbQuery();
        for (int i = 0; i < minterm.size(); i++) {
            ConditionNode n = minterm.get(i);
            if (n == null) {
                throw new IllegalArgumentException("minterm[" + i + "] 节点不能为 null");
            }
            if (n.kind == ConditionNode.Kind.FT) {
                throw new UnsupportedOperationException(
                        "MVP 边界：ColumnarAggRowSource 不支持 FT 条件（FT 走 FulltextAggRowSource）: FT("
                                + n.ftQuery + ")");
            }
            if (n.kind == ConditionNode.Kind.IS_NULL) {
                q.where.add(new DbQueryCriterion(DbQueryOp.IS_NULL, n.ormCol, null, null, null));
                continue;
            }
            if (n.kind == ConditionNode.Kind.IS_NOT_NULL) {
                q.where.add(new DbQueryCriterion(DbQueryOp.IS_NOT_NULL, n.ormCol, null, null, null));
                continue;
            }
            q.where.add(toCriterion(n));
        }
        return q;
    }

    /**
     * 单个 ORM 节点 → {@link DbQueryCriterion}（值统一 {@code String.valueOf}）。
     */
    private static DbQueryCriterion toCriterion(ConditionNode n) {
        DslToken op = n.ormOp;
        if (op == DslToken.OP_EQ) {
            return new DbQueryCriterion(DbQueryOp.EQ, n.ormCol, String.valueOf(n.ormValueA), null, null);
        }
        if (op == DslToken.OP_NE) {
            return new DbQueryCriterion(DbQueryOp.NE, n.ormCol, String.valueOf(n.ormValueA), null, null);
        }
        if (op == DslToken.OP_GT) {
            return new DbQueryCriterion(DbQueryOp.GT, n.ormCol, String.valueOf(n.ormValueA), null, null);
        }
        if (op == DslToken.OP_GE) {
            return new DbQueryCriterion(DbQueryOp.GE, n.ormCol, String.valueOf(n.ormValueA), null, null);
        }
        if (op == DslToken.OP_LT) {
            return new DbQueryCriterion(DbQueryOp.LT, n.ormCol, String.valueOf(n.ormValueA), null, null);
        }
        if (op == DslToken.OP_LE) {
            return new DbQueryCriterion(DbQueryOp.LE, n.ormCol, String.valueOf(n.ormValueA), null, null);
        }
        if (op == DslToken.KW_BETWEEN) {
            return new DbQueryCriterion(DbQueryOp.BETWEEN, n.ormCol,
                    String.valueOf(n.ormValueA), String.valueOf(n.ormValueB), null);
        }
        if (op == DslToken.KW_IN) {
            return new DbQueryCriterion(DbQueryOp.IN, n.ormCol, null, null, toStringList(n.ormValueA));
        }
        throw new UnsupportedOperationException(
                "MVP 边界：ColumnarAggRowSource 不支持 op " + op + "（列 " + n.ormCol + "）");
    }

    /**
     * KW_IN 值（{@code Collection}）→ {@code List<String>}。
     */
    private static List<String> toStringList(Object v) {
        if (!(v instanceof Collection<?> coll)) {
            throw new IllegalArgumentException(
                    "KW_IN 值须为 Collection，got " + (v == null ? "null" : v.getClass().getName()));
        }
        List<String> out = new ArrayList<>(coll.size());
        for (Object o : coll) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    /**
     * 按 docId（rowId）升序就地排序（确定性 R6）。
     */
    private static <T extends DsTableAdapter> List<T> orderByRowId(List<T> rows) {
        if (rows == null) {
            return new ArrayList<>();
        }
        rows.sort(Comparator.comparingLong(row -> {
            Long v = row.getId();
            return v == null ? 0L : v;
        }));
        return rows;
    }
}
