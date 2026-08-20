package com.q3lives.ds.database.integration;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.q3lives.ds.database.adapter.DsTableAdapter;

/**
 *
 * @author Administrator
 */
public class QueryWrapper<T> {

    public enum Op {
        EQ,
        NE,
        GT,
        GE,
        LT,
        LE,
        BETWEEN,
        IN,
        NOT_IN,
        LIKE,
        LIKE_LEFT,
        LIKE_RIGHT,
        IS_NULL,
        IS_NOT_NULL,
        IN_SUBQUERY,
        EXISTS,
        NOT_EXISTS
    }

    public static final class Criterion {
        public final Op op;
        public final String col;
        public final Object a;
        public final Object b;

        public Criterion(Op op, String col, Object a, Object b) {
            this.op = Objects.requireNonNull(op, "op cannot be null");
            this.col = Objects.requireNonNull(col, "col cannot be null");
            this.a = a;
            this.b = b;
        }
    }

    public static final class Order {
        public final String col;
        public final boolean asc;

        public Order(String col, boolean asc) {
            this.col = Objects.requireNonNull(col, "col cannot be null");
            this.asc = asc;
        }
    }

    private final List<Criterion> criteria = new java.util.ArrayList<>();
    private final List<Order> orders = new java.util.ArrayList<>();
    private final List<String> selectCols = new java.util.ArrayList<>();
    private final Map<Class<? extends DsTableAdapter>, QueryWrapper<?>> subWrappers = new LinkedHashMap<>();
    private final List<QueryWrapper<T>> orBranches = new java.util.ArrayList<>();

    public List<Criterion> criteria() {
        return Collections.unmodifiableList(criteria);
    }

    public List<Order> orders() {
        return Collections.unmodifiableList(orders);
    }

    public List<String> selectCols() {
        return Collections.unmodifiableList(selectCols);
    }

    public List<QueryWrapper<T>> orBranches() {
        return Collections.unmodifiableList(orBranches);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void merge(QueryWrapper<?> other) {
        if (other != null) {
            criteria.addAll(other.criteria);
            orBranches.addAll((List) other.orBranches);
        }
    }

    public void or(QueryWrapper<T> branch) {
        if (branch != null) {
            orBranches.add(branch);
        }
    }

    public void gt(String colName, Object val) {
        criteria.add(new Criterion(Op.GT, colName, val, null));
    }

    public void lt(String colName, Object val) {
        criteria.add(new Criterion(Op.LT, colName, val, null));
    }

    public void ge(String colName, Object val) {
        criteria.add(new Criterion(Op.GE, colName, val, null));
    }

    public void le(String colName, Object val) {
        criteria.add(new Criterion(Op.LE, colName, val, null));
    }

    public void notIn(String colName, Collection collection) {
        criteria.add(new Criterion(Op.NOT_IN, colName, collection, null));
    }

    public void in(String colName, Collection asList) {
        criteria.add(new Criterion(Op.IN, colName, asList, null));
    }

    public void likeRight(String colName, Object val) {
        criteria.add(new Criterion(Op.LIKE_RIGHT, colName, val, null));
    }

    public void likeLeft(String colName, Object val) {
        criteria.add(new Criterion(Op.LIKE_LEFT, colName, val, null));
    }

    public void like(String colName, Object val) {
        criteria.add(new Criterion(Op.LIKE, colName, val, null));
    }

    public void ne(String colName, Object val) {
        criteria.add(new Criterion(Op.NE, colName, val, null));
    }

    public void eq(String colName, Object val) {
        criteria.add(new Criterion(Op.EQ, colName, val, null));
    }

    public void isNotNull(String colName) {
        criteria.add(new Criterion(Op.IS_NOT_NULL, colName, null, null));
    }

    public void isNull(String colName) {
        criteria.add(new Criterion(Op.IS_NULL, colName, null, null));
    }

    public void between(String colName, Object min, Object max) {
        criteria.add(new Criterion(Op.BETWEEN, colName, min, max));
    }

    public QueryWrapper getSubWrapper(Class<? extends DsTableAdapter> joinClass) {
        return subWrappers.computeIfAbsent(joinClass, k -> new QueryWrapper<>());
    }

    public void select(String cols) {
        if (cols == null || cols.isBlank()) {
            return;
        }
        String[] parts = cols.split(",");
        for (String p : parts) {
            String c = p.trim();
            if (!c.isEmpty()) {
                selectCols.add(c);
            }
        }
    }

    public void orderByAsc(String colName) {
        orders.add(new Order(colName, true));
    }

    public void orderByDesc(String colName) {
        orders.add(new Order(colName, false));
    }

    public void inSubWrapper(String colName, QueryWrapper subWrapper) {
        criteria.add(new Criterion(Op.IN_SUBQUERY, colName, subWrapper, null));
    }

    public void exists(Class<? extends DsTableAdapter> relatedClass, QueryWrapper<?> subWrapper) {
        exists(relatedClass, null, subWrapper);
    }

    public void exists(Class<? extends DsTableAdapter> relatedClass, String relatedField,
            QueryWrapper<?> subWrapper) {
        if (relatedClass == null) {
            throw new IllegalArgumentException("relatedClass cannot be null");
        }
        criteria.add(new Criterion(Op.EXISTS, relatedClass.getName(), relatedClass,
                new ExistsSpec(relatedField, subWrapper)));
    }

    public void notExists(Class<? extends DsTableAdapter> relatedClass, QueryWrapper<?> subWrapper) {
        notExists(relatedClass, null, subWrapper);
    }

    public void notExists(Class<? extends DsTableAdapter> relatedClass, String relatedField,
            QueryWrapper<?> subWrapper) {
        if (relatedClass == null) {
            throw new IllegalArgumentException("relatedClass cannot be null");
        }
        criteria.add(new Criterion(Op.NOT_EXISTS, relatedClass.getName(), relatedClass,
                new ExistsSpec(relatedField, subWrapper)));
    }

    public static final class ExistsSpec {
        public final String relatedField;
        public final QueryWrapper<?> subWrapper;

        public ExistsSpec(String relatedField, QueryWrapper<?> subWrapper) {
            this.relatedField = relatedField;
            this.subWrapper = subWrapper;
        }
    }

}
