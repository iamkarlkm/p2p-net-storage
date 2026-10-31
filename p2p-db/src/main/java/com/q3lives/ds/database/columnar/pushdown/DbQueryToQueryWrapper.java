package com.q3lives.ds.database.columnar.pushdown;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;
import javax.net.p2p.model.DbQueryOrGroup;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P50 OQ12-㉘②：{@link DbQuery} 谓词 → {@link QueryWrapper} 过滤适配器（Q1=A：复用既有 ORM 谓词引擎）。
 *
 * <p>
 * 分布式列存查询入口 {@code DB_NODE_QUERY_IDS} 与 {@code DsDatabaseClusterClient.queryIds} 原先接收
 * {@code DbQuery} 却完全忽略谓词，只能返回分区全量 id。本类把 {@code DbQuery} 全等转换为 ORM 侧
 * 既有的 {@link QueryWrapper}，再交给 {@link GenericManager#listIds(QueryWrapper)} 做「逐 id 物化 +
 * 谓词二次过滤」，从而把谓词真正下推到服务端 / 客户端本地路径，消除「返回全量 id、调用方全量物化」
 * 的浪费（对齐 {@code OrmFindRangeServerHandler} 的 QueryWrapper 过滤范式）。
 *
 * <p>
 * 语义契约：转换结果与 {@code GenericManager.findList} 1:1 等价（{@code listIds} 内部先取索引候选、
 * 再按 {@code matchesAll} 后过滤），因此服务端过滤结果 ≡ 客户端谓词在本地全量物化过滤后的结果（R12）。
 *
 * <p>
 * 边界：{@code q == null}、{@code where} 与 {@code anyOf} 均为空 → 空 wrapper → 全量透传（兼容旧行为）。
 * 谓词无命中 → 空列表（非 null）。仅转换 {@code where} 与 {@code anyOf}；{@code orderBy} 不参与过滤，
 * 输出恒按 rowId 升序由调用方保证（R11）。
 *
 * <p>
 * 无实例状态、0 新线程（R10）；仅依赖 JDK + first-party（R2）。
 *
 * @author SP3.1 Implement Agent
 * @since P50 OQ12-㉘② 服务端 queryIds 谓词过滤下推
 */
public final class DbQueryToQueryWrapper {

    private DbQueryToQueryWrapper() {
    }

    /**
     * 把 {@link DbQuery} 全等转换为 {@link QueryWrapper}（where → criteria，anyOf → orBranches）。
     *
     * @param q 待转换谓词（可 null）
     * @return 转换后的 wrapper（恒非 null；{@code q == null} 或无条件 → 空 wrapper）
     * @throws IllegalArgumentException 条件 op 为空、列名空白或 IN/NOT_IN 缺少 list
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static QueryWrapper<?> convert(DbQuery q) {
        QueryWrapper w = new QueryWrapper();
        if (q == null) {
            return w;
        }
        appendCriteria(w, q.where);
        if (q.anyOf != null) {
            for (DbQueryOrGroup group : q.anyOf) {
                if (group == null) {
                    continue;
                }
                QueryWrapper branch = new QueryWrapper();
                appendCriteria(branch, group.where);
                w.or(branch);
            }
        }
        return w;
    }

    /**
     * 判定谓词是否含有效条件（用于「无谓词 → 全量透传」的兼容分支）。
     *
     * @param q 待判定谓词（可 null）
     * @return true 当且仅当 where 或任一 anyOf 分组含条件
     */
    public static boolean hasPredicate(DbQuery q) {
        if (q == null) {
            return false;
        }
        if (q.where != null && !q.where.isEmpty()) {
            return true;
        }
        if (q.anyOf != null) {
            for (DbQueryOrGroup group : q.anyOf) {
                if (group != null && group.where != null && !group.where.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 对候选 rowId 施加谓词过滤（命中保留），保持输入顺序。
     *
     * <p>ids 为空或无有效谓词时原样返回（全量透传，兼容旧行为，只读无写盘）。
     *
     * @param entityClassName 实体类名（非空白；须为 {@link DsTableAdapter} 子类）
     * @param q               谓词（可 null）
     * @param ids             候选 rowId（可 null）
     * @return 命中 rowId 列表（非 null；保持输入顺序）
     * @throws IllegalArgumentException 实体类不存在 / 非 DsTableAdapter / 谓词非法
     */
    public static List<Long> filterIdsByPredicate(String entityClassName, DbQuery q, List<Long> ids) {
        if (ids == null || ids.isEmpty() || !hasPredicate(q)) {
            return ids == null ? new ArrayList<>() : ids;
        }
        Set<Long> matched = matchedIds(entityClassName, convert(q));
        ArrayList<Long> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            if (id != null && matched.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * 经 ORM 谓词引擎求命中 rowId 集合（{@link GenericManager#listIds(QueryWrapper)} 1:1 语义）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Set<Long> matchedIds(String entityClassName, QueryWrapper<?> wrapper) {
        Class<?> raw;
        try {
            raw = Class.forName(entityClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("entity class not found: " + entityClassName, e);
        }
        if (!DsTableAdapter.class.isAssignableFrom(raw)) {
            throw new IllegalArgumentException("entity class is not a DsTableAdapter: " + entityClassName);
        }
        GenericManager gm = new GenericManager((Class<? extends DsTableAdapter>) raw);
        List<Long> hit = gm.listIds(wrapper);
        return new HashSet<>(hit == null ? List.of() : hit);
    }

    /**
     * 逐条追加 where 条件到 wrapper。
     */
    private static void appendCriteria(QueryWrapper<?> w, List<DbQueryCriterion> where) {
        if (where == null) {
            return;
        }
        for (int i = 0; i < where.size(); i++) {
            DbQueryCriterion c = where.get(i);
            if (c == null) {
                throw new IllegalArgumentException("where[" + i + "] criterion is null");
            }
            applyOne(w, c);
        }
    }

    /**
     * 单条 {@link DbQueryCriterion} → {@link QueryWrapper} 语义方法（op/name/a/b/list 全等映射）。
     */
    private static void applyOne(QueryWrapper<?> w, DbQueryCriterion c) {
        DbQueryOp op = c.op;
        if (op == null) {
            throw new IllegalArgumentException("missing op");
        }
        String col = c.name;
        if (col == null || col.isBlank()) {
            throw new IllegalArgumentException("missing name");
        }
        switch (op) {
            case EQ -> w.eq(col, c.a);
            case NE -> w.ne(col, c.a);
            case GT -> w.gt(col, c.a);
            case GE -> w.ge(col, c.a);
            case LT -> w.lt(col, c.a);
            case LE -> w.le(col, c.a);
            case BETWEEN -> w.between(col, c.a, c.b);
            case IN -> w.in(col, requireList(op, c.list));
            case NOT_IN -> w.notIn(col, requireList(op, c.list));
            case LIKE -> w.like(col, c.a);
            case LIKE_LEFT -> w.likeLeft(col, c.a);
            case LIKE_RIGHT -> w.likeRight(col, c.a);
            case IS_NULL -> w.isNull(col);
            case IS_NOT_NULL -> w.isNotNull(col);
            default -> throw new IllegalArgumentException("unsupported op: " + op);
        }
    }

    /**
     * 校验并返回 IN/NOT_IN 取值列表。
     */
    private static Collection<String> requireList(DbQueryOp op, List<String> list) {
        if (list == null) {
            throw new IllegalArgumentException(op + " requires list");
        }
        return list;
    }
}
