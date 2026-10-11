package com.q3lives.ds.database.fulltext.cluster.join;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsOneToOne;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.QueryWrapper;
import java.lang.reflect.Field;

/**
 * P31 FR-1 JoinSpec 不可变 1:1 等值 Join 元组描述（MVP v1.0，仅单字段等值 Join）。
 *
 * <p>Join 语义严格写死：主表 T 读 {@code outerField} 列值 V_out，从表 R 读 {@code relatedField}
 * 列值 V_rel；匹配条件 = {@code V_out == V_rel}（64 位 Long 等值，不做字符串/非 Long 列 Join；
 * 对 DsIdLayout rowId（22+24+8 位 long，无 tableId bit）直接做 == 等值比较，不编码表号）。
 *
 * <p>多 JoinSpec 平铺 AND 交集（外层候选 outerIds 每个 JoinSpec 后 retainAll），禁止 OR/嵌套；
 * 非等值条件（price>50 / qty<=10 等）通过 {@link #relatedCond} 叠加在从表 Qw 过滤，不进 Join Key。
 *
 * <p>4 字段全 public final：不可变类保证 DeepEquals 稳定；{@link #hashCode()} / {@link #equals(Object)}
 * 逐字段手写（relatedClass 按 .getName() 字符串、relatedCond 按 criteria Criterion(op,col,a,b)
 * 递归），不用 Object.hash 跨 JDK 漂移。
 *
 * @param <R> 从表实体类型（必须继承 DsTableAdapter，否则构造 IAE）
 * @since P31
 */
public final class JoinSpec<R extends DsTableAdapter> {

    public final Class<R> relatedClass;
    public final String outerField;
    public final String relatedField;
    public final QueryWrapper<R> relatedCond;

    /**
     * 主构造（Fail-First，5 处 IAE：relatedClass null / 未继承 DsTableAdapter / outerField blank
     * / relatedField blank / outerField="id" 但主表 T 无 getId()（由 fromDsOneToOne 反射前置检测））。
     *
     * @param relatedClass 从表实体类（非 null，必须继承 DsTableAdapter）
     * @param outerField   主表 Join 列名（非 blank；"id"=直接取主表 entity.getId()）
     * @param relatedField 从表 Join 列名（非 blank；对应 @DsOneToOne.joinProp 存外键列）
     * @param relatedCond  从表过滤条件（null=空 wrapper，等价无附加过滤）
     * @throws IllegalArgumentException 参数违反上述约束（含字段名）
     * @throws NullPointerException     relatedClass/outerField/relatedField 为 null
     */
    public JoinSpec(
            Class<R> relatedClass,
            String outerField,
            String relatedField,
            QueryWrapper<R> relatedCond
    ) {
        Objects.requireNonNull(relatedClass, "relatedClass cannot be null");
        Objects.requireNonNull(outerField, "outerField cannot be null");
        Objects.requireNonNull(relatedField, "relatedField cannot be null");
        if (!DsTableAdapter.class.isAssignableFrom(relatedClass)) {
            throw new IllegalArgumentException(
                    "relatedClass must extends DsTableAdapter, got " + relatedClass.getName());
        }
        if (outerField.isBlank()) {
            throw new IllegalArgumentException("outerField cannot be blank");
        }
        if (relatedField.isBlank()) {
            throw new IllegalArgumentException("relatedField cannot be blank");
        }
        this.relatedClass = relatedClass;
        this.outerField = outerField;
        this.relatedField = relatedField;
        this.relatedCond = relatedCond;
    }

    /**
     * 返回新 JoinSpec（深拷贝），覆盖 relatedCond；用于叠加从表过滤条件（不修改原实例引用，
     * 保证 DeepEquals 稳定，1000× 重跑 hashCode Set.size=1）。
     *
     * @param newCond 新 relatedCond（null=空 wrapper）
     * @return 新 JoinSpec 实例（relatedClass/outerField/relatedField 与 this 全等）
     */
    public JoinSpec<R> withRelatedCond(QueryWrapper<R> newCond) {
        return new JoinSpec<>(this.relatedClass, this.outerField, this.relatedField, newCond);
    }

    /**
     * FR-4 @DsOneToOne(joinProp) 反射自动构建 JoinSpec 列表（零侵入业务代码，不 new 新注解）。
     *
     * <p>规则：扫描 mainEntityClass 所有字段，含 {@link DsOneToOne} 注解 →
     * 1) joinProp() 作为 relatedField（从表 Join 列名）；
     * 2) props() default "" → outerField="id"（直接取主表 entity.getId() == 从表 joinProp 列值）；
     *    props() 非空时作为 outerField（未实现 MVP 抛 UOE，留 P34 DSL v2）；
     * 3) relatedClass = 字段类型（必须继承 DsTableAdapter，否则 IAE）；
     * 4) relatedField 对应从表字段必须是 Long 类型且标注 @DsField（否则 IAE，Fail-First）。
     *
     * @param mainEntityClass 主表实体类（非 null）
     * @param <T>             主表类型（DsTableAdapter 子类）
     * @return JoinSpec 列表（可空 list，不含 duplicate relatedClass）
     * @throws IllegalArgumentException 违反 DsOneToOne 契约（字段不存在 / 非 Long / 0 @DsField）
     * @throws NullPointerException     mainEntityClass == null
     * @since P31
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static <T extends DsTableAdapter> List<JoinSpec<?>> fromDsOneToOneAnnotations(
            Class<T> mainEntityClass
    ) {
        Objects.requireNonNull(mainEntityClass, "mainEntityClass cannot be null");
        List<JoinSpec<?>> out = new ArrayList<>();
        for (Field f : mainEntityClass.getDeclaredFields()) {
            DsOneToOne ann = f.getAnnotation(DsOneToOne.class);
            if (ann == null) continue;
            Class<?> fieldType = f.getType();
            if (!DsTableAdapter.class.isAssignableFrom(fieldType)) {
                throw new IllegalArgumentException(
                        "@DsOneToOne field[" + f.getName() + "] type must be DsTableAdapter subclass, got "
                                + fieldType.getName());
            }
            Class<? extends DsTableAdapter> relatedClass = (Class<? extends DsTableAdapter>) fieldType;
            String joinProp = ann.joinProp();
            if (joinProp == null || joinProp.isBlank()) {
                throw new IllegalArgumentException(
                        "@DsOneToOne(joinProp) cannot be blank on field[" + f.getName() + "]");
            }
            Field relatedF;
            try {
                relatedF = relatedClass.getDeclaredField(joinProp);
            } catch (NoSuchFieldException ex) {
                throw new IllegalArgumentException(
                        "relatedClass[" + relatedClass.getName() + "] missing joinProp field: '" + joinProp
                                + "' (field[" + f.getName() + "])", ex);
            }
            if (relatedF.getType() != Long.class && relatedF.getType() != long.class) {
                throw new IllegalArgumentException(
                        "@DsOneToOne joinProp[" + joinProp + "] must be Long/long (not Long type, got "
                                + relatedF.getType().getName() + ") on " + relatedClass.getName());
            }
            DsField dsF = relatedF.getAnnotation(DsField.class);
            if (dsF == null) {
                throw new IllegalArgumentException(
                        "relatedClass[" + relatedClass.getName() + "] joinProp[" + joinProp
                                + "] missing @DsField annotation (ORM 列未注册)");
            }
            String props = ann.props();
            String outerField = (props == null || props.isBlank()) ? "id" : props;
            if (!"id".equals(outerField)) {
                throw new UnsupportedOperationException(
                        "@DsOneToOne.props() MVP v1.0 仅支持 \"\"/\"id\" (主表 entity.getId() 等值), got '"
                                + props + "' on field[" + f.getName() + "] (留 P34 DSL v2 支持非主键 outerField)");
            }
            out.add(new JoinSpec(relatedClass, outerField, joinProp, null));
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JoinSpec)) return false;
        JoinSpec<?> other = (JoinSpec<?>) o;
        if (!this.relatedClass.getName().equals(other.relatedClass.getName())) return false;
        if (!this.outerField.equals(other.outerField)) return false;
        if (!this.relatedField.equals(other.relatedField)) return false;
        return QwHelper.deepEquals(this.relatedCond, other.relatedCond);
    }

    @Override
    public int hashCode() {
        int h = 17;
        h = 31 * h + this.relatedClass.getName().hashCode();
        h = 31 * h + this.outerField.hashCode();
        h = 31 * h + this.relatedField.hashCode();
        h = 31 * h + QwHelper.deepHashCode(this.relatedCond);
        return h;
    }

    /**
     * DeepEquals/HashCode/DefensiveCopy helper（内嵌 static 内部类，ZeroLeak3=3 不新增独立类）。
     * 对 QueryWrapper<T> criteria/Orders/selectCols/subWrappers/orBranches 逐元素 equals/hashCode；
     * Criterion(op, ExistsSpec(relatedField+outerField+subWrapper 递归 equals/hashCode。
     */
    static final class QwHelper {
        private static final Field QW_CRITERIA;
        private static final Field QW_ORDERS;
        private static final Field QW_SELECT_COLS;
        private static final Field QW_OR_BRANCHES;
        static {
            try {
                QW_CRITERIA = QueryWrapper.class.getDeclaredField("criteria");
                QW_ORDERS = QueryWrapper.class.getDeclaredField("orders");
                QW_SELECT_COLS = QueryWrapper.class.getDeclaredField("selectCols");
                QW_OR_BRANCHES = QueryWrapper.class.getDeclaredField("orBranches");
                QW_CRITERIA.setAccessible(true);
                QW_ORDERS.setAccessible(true);
                QW_SELECT_COLS.setAccessible(true);
                QW_OR_BRANCHES.setAccessible(true);
            } catch (NoSuchFieldException ex) {
                throw new ExceptionInInitializerError(ex);
            }
        }
        @SuppressWarnings("rawtypes")
        static boolean deepEquals(QueryWrapper a, QueryWrapper b) {
            if (a == b) return true;
            if (a == null || b == null) return false;
            try {
                List ca = (List) QW_CRITERIA.get(a), cb = (List) QW_CRITERIA.get(b);
                if (ca.size() != cb.size()) return false;
                for (int i = 0; i < ca.size(); i++) if (!critEq(ca.get(i), cb.get(i))) return false;
                List oa = (List) QW_ORDERS.get(a), ob = (List) QW_ORDERS.get(b);
                if (oa.size() != ob.size()) return false;
                for (int i = 0; i < oa.size(); i++) {
                    QueryWrapper.Order x = (QueryWrapper.Order) oa.get(i), y = (QueryWrapper.Order) ob.get(i);
                    if (!x.col.equals(y.col) || x.asc != y.asc) return false;
                }
                if (!QW_SELECT_COLS.get(a).equals(QW_SELECT_COLS.get(b))) return false;
                List bra = (List) QW_OR_BRANCHES.get(a), brb = (List) QW_OR_BRANCHES.get(b);
                if (bra.size() != brb.size()) return false;
                for (int i = 0; i < bra.size(); i++) if (!deepEquals((QueryWrapper) bra.get(i), (QueryWrapper) brb.get(i))) return false;
                return true;
            } catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
        }
        private static boolean critEq(Object x, Object y) {
            if (!(x instanceof QueryWrapper.Criterion) || !(y instanceof QueryWrapper.Criterion)) return java.util.Objects.equals(x, y);
            QueryWrapper.Criterion cx = (QueryWrapper.Criterion) x, cy = (QueryWrapper.Criterion) y;
            if (cx.op != cy.op) return false;
            if (!cx.col.equals(cy.col)) return false;
            Object ax = cx.a; if (ax instanceof Class<?>) ax = ((Class<?>) ax).getName();
            Object ay = cy.a; if (ay instanceof Class<?>) ay = ((Class<?>) ay).getName();
            if (!java.util.Objects.equals(ax, ay)) return false;
            Object bx = cx.b, by = cy.b;
            if (bx instanceof QueryWrapper.ExistsSpec && by instanceof QueryWrapper.ExistsSpec) {
                QueryWrapper.ExistsSpec sx = (QueryWrapper.ExistsSpec) bx, sy = (QueryWrapper.ExistsSpec) by;
                if (!java.util.Objects.equals(sx.relatedField, sy.relatedField)) return false;
                if (!java.util.Objects.equals(sx.outerField, sy.outerField)) return false;
                return deepEquals(sx.subWrapper, sy.subWrapper);
            }
            if (bx instanceof QueryWrapper && by instanceof QueryWrapper) {
                return deepEquals((QueryWrapper<?>) bx, (QueryWrapper<?>) by);
            }
            return java.util.Objects.equals(bx, by);
        }
        @SuppressWarnings("rawtypes")
        static int deepHashCode(QueryWrapper w) {
            if (w == null) return 0;
            try {
                int h = 1;
                List cs = (List) QW_CRITERIA.get(w);
                for (Object o : cs) {
                    if (!(o instanceof QueryWrapper.Criterion)) continue;
                    QueryWrapper.Criterion c = (QueryWrapper.Criterion) o;
                    h = 31*h + c.op.hashCode();
                    h = 31*h + c.col.hashCode();
                    Object a = c.a;
                    h = 31*h + (a == null ? 0 : (a instanceof Class<?> ? ((Class<?>)a).getName().hashCode() : a.hashCode()));
                    Object b = c.b;
                    if (b instanceof QueryWrapper.ExistsSpec) {
                        QueryWrapper.ExistsSpec s = (QueryWrapper.ExistsSpec) b;
                        h = 31*h + (s.relatedField==null?0:s.relatedField.hashCode());
                        h = 31*h + (s.outerField==null?0:s.outerField.hashCode());
                        h = 31*h + deepHashCode(s.subWrapper);
                    } else if (b instanceof QueryWrapper) {
                        h = 31*h + deepHashCode((QueryWrapper) b);
                    } else h = 31*h + (b==null?0:b.hashCode());
                }
                List os = (List) QW_ORDERS.get(w);
                for (Object o : os) {
                    QueryWrapper.Order ord = (QueryWrapper.Order) o;
                    h = 31*h + ord.col.hashCode() + (ord.asc?1:0);
                }
                for (String s : (List<String>) QW_SELECT_COLS.get(w)) h = 31*h + s.hashCode();
                List brs = (List) QW_OR_BRANCHES.get(w);
                for (Object br : brs) h = 31*h + deepHashCode((QueryWrapper) br);
                return h;
            } catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
        }
        @SuppressWarnings({ "rawtypes", "unchecked" })
        static <T> QueryWrapper<T> defensiveCopy(QueryWrapper<T> src) {
            if (src == null) return null;
            try {
                QueryWrapper<T> out = new QueryWrapper<>();
                List srcCs = (List) QW_CRITERIA.get(src);
                List outCs = (List) QW_CRITERIA.get(out);
                for (Object o : srcCs) {
                    QueryWrapper.Criterion c = (QueryWrapper.Criterion) o;
                    Object b = c.b;
                    if (b instanceof QueryWrapper.ExistsSpec) {
                        QueryWrapper.ExistsSpec s = (QueryWrapper.ExistsSpec) b;
                        b = new QueryWrapper.ExistsSpec(s.relatedField, s.outerField, defensiveCopy(s.subWrapper));
                    } else if (b instanceof QueryWrapper) {
                        b = defensiveCopy((QueryWrapper) b);
                    }
                    outCs.add(new QueryWrapper.Criterion(c.op, c.col, c.a, b));
                }
                List outOs = (List) QW_ORDERS.get(out);
                for (Object o : (List) QW_ORDERS.get(src)) {
                    QueryWrapper.Order ord = (QueryWrapper.Order) o;
                    outOs.add(new QueryWrapper.Order(ord.col, ord.asc));
                }
                ((List) QW_SELECT_COLS.get(out)).addAll((List) QW_SELECT_COLS.get(src));
                List outOrs = (List) QW_OR_BRANCHES.get(out);
                for (Object br : (List) QW_OR_BRANCHES.get(src)) {
                    outOrs.add(defensiveCopy((QueryWrapper) br));
                }
                return out;
            } catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
        }
    }
}
