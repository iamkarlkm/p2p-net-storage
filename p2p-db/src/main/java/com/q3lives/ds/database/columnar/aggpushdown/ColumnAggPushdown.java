package com.q3lives.ds.database.columnar.aggpushdown;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.pushdown.ColumnScanPushdown;

import javax.net.p2p.model.DbAggFn;
import javax.net.p2p.model.DbAggItem;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;
import javax.net.p2p.model.DbQueryOrder;

/**
 * P53 OQ12-㉛②：列值聚合下推引擎（服务端在列存上直接算聚合，消除逐 id 物化）。
 *
 * <p>
 * 目标：把 P45/P46 遗留的「客户端逐 id {@code getById} 物化 + 实体级 {@code AggExecutor} 聚合」升级为
 * 「服务端在列值上直接算聚合」，由服务端回传「分组键列 + 聚合值列」矩阵。命中 rowId 由 P52
 * {@link ColumnScanPushdown#filter} 先过滤得到（0 改复用），本引擎只做列值读取 + 分桶 + 聚合 + HAVING +
 * ORDER BY + 分页。</p>
 *
 * <p>
 * 语义口径（严格对齐 P39 {@code AggExecutor}，R7）：列值为 NULL（{@code getValue} 返回 null 或解码后
 * 非数值）记为 {@code NaN} 并跳过；{@code COUNT(col)} 只统计非 NULL 值、空组为 {@code 0}；
 * {@code MIN/MAX/SUM/AVG} 忽略 NULL、空组为
 * {@code 0.0}；{@code AVG = sum / count}（count 为 0 时 0.0）。 无分组（{@code groupBy}
 * 为空）时恒输出一行全局结果。</p>
 *
 * <p>
 * 结果行布局：{@code Object[] row}，长度 =
 * {@code groupBy.size() + aggs.size()}；前段为分组键值（按 {@code groupBy} 声明序，保留列
 * {@code javaType} 解码后的类型），后段为聚合值（恒为 {@code Double}， 按 {@code aggs} 声明序）。默认排序 =
 * 分组键升序（R6 确定性）；{@code orderBy} 非空时按其全序稳定排序 （tie-break 回落到默认序）。</p>
 *
 * <p>
 * 只读无写盘（R9）、0 新线程（R10）、零实例状态（唯一私有构造）、仅依赖 JDK + first-party（R2/NG-3）。 编码
 * {@link #encode} / 解码 {@link #decode} 成对提供，供 {@code DbRowAggResponse.payload}
 * 跨进程往返。</p>
 *
 * @author SP3.1 Implement Agent
 * @since P53 OQ12-㉛② 服务端聚合函数下发 / ORDER BY 服务端排序
 */
public final class ColumnAggPushdown {

    /**
     * 单元格类型标签：NULL。
     */
    private static final byte T_NULL = 0;

    /**
     * 单元格类型标签：LONG。
     */
    private static final byte T_LONG = 1;

    /**
     * 单元格类型标签：INT。
     */
    private static final byte T_INT = 2;

    /**
     * 单元格类型标签：DOUBLE。
     */
    private static final byte T_DOUBLE = 3;

    /**
     * 单元格类型标签：BOOLEAN。
     */
    private static final byte T_BOOLEAN = 4;

    /**
     * 单元格类型标签：STRING。
     */
    private static final byte T_STRING = 5;

    /**
     * 默认返回条数（{@code limit <= 0} 时启用），与 {@link ColumnScanPushdown#scan} 口径一致。
     */
    private static final int DEFAULT_LIMIT = 100;

    private ColumnAggPushdown() {
    }

    /**
     * 列值聚合下推：对命中 rowId 集合做分组聚合 + HAVING + ORDER BY + 分页。
     *
     * @param dbRoot 分区列存根目录（非 null）
     * @param entityClassName 实体类名（非空白）
     * @param rowIds 命中 rowId（可 null / 空；由 {@link ColumnScanPushdown#filter} 提供）
     * @param aggs 聚合项（非 null / 非空；声明序 = 输出聚合列序）
     * @param groupBy 分组键列名（可 null；空 = 全局聚合输出一行）
     * @param having 分组后过滤（可 null；左值为分组键列名或聚合别名 {@code FN(col)}）
     * @param orderBy 结果排序（可 null；按分组键列名或聚合别名 {@code FN(col)}）
     * @param offset 偏移（负值按 0 处理）
     * @param limit 条数（{@code <= 0} 按 100 处理）
     * @return 分组结果行（非 null；每行 = 分组键[] + 聚合值[]）
     * @throws IllegalArgumentException dbRoot 为 null / entityClassName 空白 /
     * aggs 空 / groupBy 或聚合项引用未知列 / HAVING 引用未知列
     */
    public static List<Object[]> compute(File dbRoot, String entityClassName, List<Long> rowIds,
            List<DbAggItem> aggs, List<String> groupBy, List<DbQueryCriterion> having,
            List<DbQueryOrder> orderBy, int offset, int limit) {
        requireDbRoot(dbRoot);
        requireEntity(entityClassName);
        requireAggs(aggs);
        List<String> groups = groupBy == null ? List.of() : groupBy;
        int g = groups.size();
        int n = aggs.size();

        TableMetaStore.TableMeta meta = new TableMetaStore(dbRoot).getMeta(entityClassName);
        Map<String, TableMetaStore.ColumnDef> defs = ColumnScanPushdown.buildColumnDefs(meta);
        for (int i = 0; i < g; i++) {
            String col = groups.get(i);
            if (col == null || col.isBlank()) {
                throw new IllegalArgumentException("groupBy[" + i + "] is blank");
            }
            if (!defs.containsKey(col)) {
                throw new IllegalArgumentException("unknown column: " + col);
            }
        }
        for (int j = 0; j < n; j++) {
            DbAggItem item = aggs.get(j);
            if (item == null || item.fn == null || item.column == null || item.column.isBlank()) {
                throw new IllegalArgumentException("aggs[" + j + "] is invalid");
            }
            if (!defs.containsKey(item.column)) {
                throw new IllegalArgumentException("unknown column: " + item.column);
            }
        }

        ArrayList<Object[]> rows = new ArrayList<>();
        if (rowIds != null && !rowIds.isEmpty()) {
            try {
                ColumnarStore store = new ColumnarStore(dbRoot);
                LinkedHashMap<List<Object>, Acc> buckets = new LinkedHashMap<>();
                for (Long rowId : rowIds) {
                    if (rowId == null || rowId <= 0L) {
                        continue;
                    }
                    Object[] key = new Object[g];
                    for (int i = 0; i < g; i++) {
                        key[i] = readColumn(store, entityClassName, groups.get(i), rowId, defs);
                    }
                    Acc acc = buckets.computeIfAbsent(Arrays.asList(key), k -> new Acc(n));
                    for (int j = 0; j < n; j++) {
                        double x = toDouble(readColumn(store, entityClassName, aggs.get(j).column, rowId, defs));
                        if (Double.isNaN(x)) {
                            continue;
                        }
                        if (acc.counts[j] == 0) {
                            acc.mins[j] = x;
                            acc.maxs[j] = x;
                        } else {
                            if (x < acc.mins[j]) {
                                acc.mins[j] = x;
                            }
                            if (x > acc.maxs[j]) {
                                acc.maxs[j] = x;
                            }
                        }
                        acc.counts[j]++;
                        acc.sums[j] += x;
                    }
                }
                for (Map.Entry<List<Object>, Acc> e : buckets.entrySet()) {
                    rows.add(toRow(e.getKey(), e.getValue(), aggs, g, n));
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("column agg failed: " + entityClassName, e);
            }
        }
        if (g == 0 && rows.isEmpty()) {
            rows.add(newZeroRow(n));
        }

        if (having != null && !having.isEmpty()) {
            ArrayList<Object[]> kept = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                if (matchesHaving(row, having, groups, aggs)) {
                    kept.add(row);
                }
            }
            rows = kept;
        }

        rows.sort(groupKeyComparator(g));
        if (orderBy != null && !orderBy.isEmpty()) {
            rows.sort(orderByComparator(orderBy, groups, aggs));
        }

        int from = Math.min(Math.max(0, offset), rows.size());
        int to = Math.min(from + (limit <= 0 ? DEFAULT_LIMIT : limit), rows.size());
        return new ArrayList<>(rows.subList(from, to));
    }

    /**
     * 编码分组结果矩阵为紧凑二进制（行数 + 逐行「列数 + 逐格类型标签 + 值」）。
     *
     * @param rows 分组结果行（可 null / 空）
     * @return 编码字节（非 null）
     */
    public static byte[] encode(List<Object[]> rows) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(rows == null ? 0 : rows.size());
            if (rows != null) {
                for (Object[] row : rows) {
                    int cells = row == null ? 0 : row.length;
                    out.writeInt(cells);
                    for (int i = 0; i < cells; i++) {
                        writeCell(out, row[i]);
                    }
                }
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("encode agg result failed", e);
        }
    }

    /**
     * 解码 {@link #encode} 产物为分组结果矩阵。
     *
     * @param bytes 编码字节（可 null / 空）
     * @return 分组结果行（非 null）
     */
    public static List<Object[]> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new ArrayList<>();
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int rows = in.readInt();
            ArrayList<Object[]> out = new ArrayList<>(Math.max(0, rows));
            for (int r = 0; r < rows; r++) {
                int cells = in.readInt();
                Object[] row = new Object[cells];
                for (int i = 0; i < cells; i++) {
                    row[i] = readCell(in);
                }
                out.add(row);
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("decode agg result failed", e);
        }
    }

    private static void writeCell(DataOutputStream out, Object v) throws IOException {
        if (v == null) {
            out.writeByte(T_NULL);
            return;
        }
        if (v instanceof Long l) {
            out.writeByte(T_LONG);
            out.writeLong(l);
            return;
        }
        if (v instanceof Integer i) {
            out.writeByte(T_INT);
            out.writeInt(i);
            return;
        }
        if (v instanceof Number num) {
            out.writeByte(T_DOUBLE);
            out.writeDouble(num.doubleValue());
            return;
        }
        if (v instanceof Boolean b) {
            out.writeByte(T_BOOLEAN);
            out.writeBoolean(b);
            return;
        }
        byte[] s = String.valueOf(v).getBytes(StandardCharsets.UTF_8);
        out.writeByte(T_STRING);
        out.writeInt(s.length);
        out.write(s);
    }

    private static Object readCell(DataInputStream in) throws IOException {
        byte tag = in.readByte();
        switch (tag) {
            case T_NULL:
                return null;
            case T_LONG:
                return in.readLong();
            case T_INT:
                return in.readInt();
            case T_DOUBLE:
                return in.readDouble();
            case T_BOOLEAN:
                return in.readBoolean();
            case T_STRING:
                int len = in.readInt();
                byte[] s = new byte[len];
                in.readFully(s);
                return new String(s, StandardCharsets.UTF_8);
            default:
                throw new IllegalStateException("unknown cell tag: " + tag);
        }
    }

    /**
     * 读取某行的某列值（列元 javaType 解码）；NULL 列值返回 null。
     */
    private static Object readColumn(ColumnarStore store, String entityClassName, String column, long rowId,
            Map<String, TableMetaStore.ColumnDef> defs) throws Exception {
        TableMetaStore.ColumnDef def = defs.get(column);
        byte[] bytes = store.getValue(entityClassName, column, rowId);
        return bytes == null ? null : ColumnScanPushdown.decode(def.javaType, bytes);
    }

    /**
     * 列值 → double；NULL / 非数值（字符串列）→ {@link Double#NaN}（聚合时跳过）。
     */
    private static double toDouble(Object v) {
        if (v instanceof Number num) {
            return num.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        return Double.NaN;
    }

    private static Object[] toRow(List<Object> key, Acc acc, List<DbAggItem> aggs, int g, int n) {
        Object[] row = new Object[g + n];
        for (int i = 0; i < g; i++) {
            row[i] = key.get(i);
        }
        for (int j = 0; j < n; j++) {
            row[g + j] = aggregateValue(aggs.get(j).fn, acc, j);
        }
        return row;
    }

    /**
     * 单聚合项取值（严格对齐 P39 {@code AggExecutor} 输出公式）。
     */
    private static double aggregateValue(DbAggFn fn, Acc acc, int j) {
        long c = acc.counts[j];
        double sm = fn == DbAggFn.COUNT ? 0.0 : acc.sums[j];
        if (fn == DbAggFn.COUNT) {
            return (double) c;
        }
        if (fn == DbAggFn.SUM) {
            return sm;
        }
        if (fn == DbAggFn.AVG) {
            return c == 0 ? 0.0 : acc.sums[j] / c;
        }
        if (fn == DbAggFn.MIN) {
            return c == 0 ? 0.0 : acc.mins[j];
        }
        if (fn == DbAggFn.MAX) {
            return c == 0 ? 0.0 : acc.maxs[j];
        }
        throw new IllegalStateException("unknown DbAggFn: " + fn);
    }

    private static Object[] newZeroRow(int n) {
        Object[] row = new Object[n];
        for (int j = 0; j < n; j++) {
            row[j] = 0.0;
        }
        return row;
    }

    private static Comparator<Object[]> groupKeyComparator(int g) {
        return (a, b) -> {
            for (int i = 0; i < g; i++) {
                int cmp = compareValues(a[i], b[i]);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        };
    }

    private static Comparator<Object[]> orderByComparator(
            List<DbQueryOrder> orders, List<String> groups, List<DbAggItem> aggs) {
        return (a, b) -> {
            for (DbQueryOrder o : orders) {
                int cmp = compareValues(orderKey(o, a, groups, aggs), orderKey(o, b, groups, aggs));
                if (cmp != 0) {
                    return (o != null && !o.asc) ? -cmp : cmp;
                }
            }
            return 0;
        };
    }

    private static Object orderKey(DbQueryOrder o, Object[] row, List<String> groups, List<DbAggItem> aggs) {
        if (o == null || o.name == null || o.name.isBlank()) {
            return null;
        }
        int gi = groups.indexOf(o.name);
        if (gi >= 0) {
            return row[gi];
        }
        int g = groups.size();
        for (int j = 0; j < aggs.size(); j++) {
            if (o.name.equalsIgnoreCase(aggs.get(j).alias())) {
                return row[g + j];
            }
        }
        return null;
    }

    private static boolean matchesHaving(
            Object[] row, List<DbQueryCriterion> having, List<String> groups, List<DbAggItem> aggs) {
        for (int i = 0; i < having.size(); i++) {
            DbQueryCriterion c = having.get(i);
            if (c == null) {
                throw new IllegalArgumentException("having[" + i + "] is null");
            }
            if (!havingOne(row, c, groups, aggs)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean havingOne(
            Object[] row, DbQueryCriterion c, List<String> groups, List<DbAggItem> aggs) {
        if (c.op == null) {
            throw new IllegalArgumentException("missing op");
        }
        if (c.name == null || c.name.isBlank()) {
            throw new IllegalArgumentException("missing name");
        }
        Object left = havingLeft(c.name, row, groups, aggs);
        DbQueryOp op = c.op;
        if (op == DbQueryOp.IS_NULL) {
            return left == null;
        }
        if (op == DbQueryOp.IS_NOT_NULL) {
            return left != null;
        }
        if (left == null) {
            return false;
        }
        if ((op == DbQueryOp.IN || op == DbQueryOp.NOT_IN) && c.list == null) {
            throw new IllegalArgumentException(op + " requires list");
        }
        Object a = coerce(left, c.a);
        if (op == DbQueryOp.EQ) {
            return Objects.equals(left, a);
        }
        if (op == DbQueryOp.NE) {
            return !Objects.equals(left, a);
        }
        if (left instanceof Comparable) {
            Comparable cp = (Comparable) left;
            if (op == DbQueryOp.GT) {
                return cp.compareTo(a) > 0;
            }
            if (op == DbQueryOp.GE) {
                return cp.compareTo(a) >= 0;
            }
            if (op == DbQueryOp.LT) {
                return cp.compareTo(a) < 0;
            }
            if (op == DbQueryOp.LE) {
                return cp.compareTo(a) <= 0;
            }
            if (op == DbQueryOp.BETWEEN) {
                return cp.compareTo(a) >= 0 && cp.compareTo(coerce(left, c.b)) <= 0;
            }
        }
        if (op == DbQueryOp.IN) {
            for (String s : c.list) {
                if (Objects.equals(left, coerce(left, s))) {
                    return true;
                }
            }
            return false;
        }
        if (op == DbQueryOp.NOT_IN) {
            for (String s : c.list) {
                if (Objects.equals(left, coerce(left, s))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof String s) {
            String q = c.a == null ? "" : c.a;
            if (op == DbQueryOp.LIKE) {
                return s.contains(q);
            }
            if (op == DbQueryOp.LIKE_LEFT) {
                return s.startsWith(q);
            }
            if (op == DbQueryOp.LIKE_RIGHT) {
                return s.endsWith(q);
            }
        }
        return false;
    }

    private static Object havingLeft(String name, Object[] row, List<String> groups, List<DbAggItem> aggs) {
        int gi = groups.indexOf(name);
        if (gi >= 0) {
            return row[gi];
        }
        int g = groups.size();
        for (int j = 0; j < aggs.size(); j++) {
            if (name.equalsIgnoreCase(aggs.get(j).alias())) {
                return row[g + j];
            }
        }
        throw new IllegalArgumentException("unknown having column: " + name);
    }

    /**
     * 把 HAVING 右值文本按其左值运行时类型解析（Long/Integer/Double/Boolean/字符串）。
     */
    private static Object coerce(Object left, String text) {
        if (text == null) {
            return null;
        }
        if (left instanceof Long) {
            return Long.parseLong(text);
        }
        if (left instanceof Integer) {
            return Integer.parseInt(text);
        }
        if (left instanceof Double) {
            return Double.parseDouble(text);
        }
        if (left instanceof Boolean) {
            return Boolean.parseBoolean(text);
        }
        return text;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b) {
        if (a == b) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Comparable ca && b.getClass().isAssignableFrom(a.getClass())) {
            return ca.compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static void requireDbRoot(File dbRoot) {
        if (dbRoot == null) {
            throw new IllegalArgumentException("dbRoot cannot be null");
        }
    }

    private static void requireEntity(String entityClassName) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
    }

    private static void requireAggs(List<DbAggItem> aggs) {
        if (aggs == null || aggs.isEmpty()) {
            throw new IllegalArgumentException("aggs cannot be empty");
        }
    }

    /**
     * 单组累加器（长整/双精度定长数组，零装箱）。
     */
    private static final class Acc {

        final long[] counts;
        final double[] sums;
        final double[] mins;
        final double[] maxs;

        Acc(int n) {
            this.counts = new long[n];
            this.sums = new double[n];
            this.mins = new double[n];
            this.maxs = new double[n];
        }
    }
}
