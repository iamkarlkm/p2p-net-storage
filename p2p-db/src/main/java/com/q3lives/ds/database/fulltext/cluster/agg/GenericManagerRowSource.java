package com.q3lives.ds.database.fulltext.cluster.agg;

import java.util.Collection;
import java.util.List;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * GenericManagerRowSource ORM 行源实装（P39 OQ1 DSL v3 聚合执行/合并引擎）。
 *
 * <p>
 * 把一条 minterm（AND 条件列表）翻译成 {@link QueryWrapper}，交由 {@link GenericManager#findList}
 * 取值（本地 / 分布式同一签名，0 改 GenericManager）。执行的 minterm 语义与 DNF 一致：节点间 AND。
 *
 * <p>
 * 节点映射：
 * <ul>
 * <li>{@code Kind.ORM}：{@code OP_EQ→eq / OP_NE→ne / OP_GT→gt / OP_GE→ge / OP_LT→lt / OP_LE→le /
 * KW_BETWEEN→between / KW_IN→in}；其余 op（如 {@code KW_IS} / {@code KW_LIKE}）→
 * {@link UnsupportedOperationException}</li>
 * <li>{@code Kind.IS_NULL → isNull} / {@code Kind.IS_NOT_NULL → isNotNull}</li>
 * <li>{@code Kind.FT} → {@link UnsupportedOperationException}（MVP 边界：FT 条件行源由业务方实现
 * {@link AggRowSource}）</li>
 * </ul>
 *
 * @param <T> ORM 实体类型
 * @author SP3.1 Implement Agent
 * @since P39 OQ1 DSL v3 聚合执行/合并引擎 MVP
 */
public final class GenericManagerRowSource<T extends DsTableAdapter> implements AggRowSource<T> {

    private final GenericManager<T> gm;

    /**
     * 构造 ORM 行源。
     *
     * @param gm 通用管理器（非 null）
     * @throws IllegalArgumentException {@code gm == null}
     */
    public GenericManagerRowSource(GenericManager<T> gm) {
        if (gm == null) {
            throw new IllegalArgumentException("GenericManagerRowSource gm cannot be null");
        }
        this.gm = gm;
    }

    @Override
    public List<T> fetch(List<ConditionNode> minterm) {
        return gm.findList(toWrapper(minterm));
    }

    /**
     * minterm（AND 条件列表）→ {@link QueryWrapper}（WHERE）。
     *
     * @param minterm 条件列表（非 null 非空，无 null 节点）
     * @return 等价的 QueryWrapper
     * @throws IllegalArgumentException {@code minterm == null / isEmpty / 含 null 节点}
     * @throws UnsupportedOperationException 含 FT 节点，或 ormOp 为 KW_IS / KW_LIKE 等未映射 op
     */
    static <T extends DsTableAdapter> QueryWrapper<T> toWrapper(List<ConditionNode> minterm) {
        if (minterm == null) {
            throw new IllegalArgumentException("minterm cannot be null");
        }
        if (minterm.isEmpty()) {
            throw new IllegalArgumentException("minterm 不能为空（至少 1 个 ConditionNode）");
        }
        QueryWrapper<T> w = new QueryWrapper<>();
        for (int i = 0; i < minterm.size(); i++) {
            ConditionNode n = minterm.get(i);
            if (n == null) {
                throw new IllegalArgumentException("minterm[" + i + "] cannot be null");
            }
            if (n.kind == ConditionNode.Kind.FT) {
                throw new UnsupportedOperationException(
                        "MVP 边界：GenericManagerRowSource 不支持 FT 条件（请自行实现 AggRowSource）: FT("
                                + n.ftQuery + ")");
            }
            if (n.kind == ConditionNode.Kind.IS_NULL) {
                w.isNull(n.ormCol);
                continue;
            }
            if (n.kind == ConditionNode.Kind.IS_NOT_NULL) {
                w.isNotNull(n.ormCol);
                continue;
            }
            DslToken op = n.ormOp;
            if (op == DslToken.OP_EQ) {
                w.eq(n.ormCol, n.ormValueA);
            } else if (op == DslToken.OP_NE) {
                w.ne(n.ormCol, n.ormValueA);
            } else if (op == DslToken.OP_GT) {
                w.gt(n.ormCol, n.ormValueA);
            } else if (op == DslToken.OP_GE) {
                w.ge(n.ormCol, n.ormValueA);
            } else if (op == DslToken.OP_LT) {
                w.lt(n.ormCol, n.ormValueA);
            } else if (op == DslToken.OP_LE) {
                w.le(n.ormCol, n.ormValueA);
            } else if (op == DslToken.KW_BETWEEN) {
                w.between(n.ormCol, n.ormValueA, n.ormValueB);
            } else if (op == DslToken.KW_IN) {
                w.in(n.ormCol, (Collection<?>) n.ormValueA);
            } else {
                throw new UnsupportedOperationException(
                        "MVP 边界：GenericManagerRowSource 不支持 op " + op + "（列 " + n.ormCol + "）");
            }
        }
        return w;
    }
}
