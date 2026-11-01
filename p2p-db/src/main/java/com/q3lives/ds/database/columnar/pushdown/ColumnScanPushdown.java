package com.q3lives.ds.database.columnar.pushdown;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexStore;
import com.q3lives.ds.database.schema.DynamicIndexUtil;

import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;
import javax.net.p2p.model.DbQueryOrGroup;

/**
 * P52 OQ12-㉚①：列扫描下推器（列索引候选 + 列值级残余校验）。
 *
 * <p>
 * 目标：把 {@code DB_NODE_QUERY_IDS} 与 {@code DsDatabaseClusterClient.queryIds} 的谓词过滤从 ORM
 * 「逐 id {@code getTable} 全行物化 + {@code matchesAll}」升级为「列级等值索引候选求交 + 列值残差校验」，
 * 消除全行物化开销，且与 P50 现行为 R12 逐条等价。</p>
 *
 * <p>
 * 候选优先级：EQ/IN → {@link EqIndexStore#findRowIds} 索引求交（取最小集合为基准做交集）；NOT_IN →
 * 索引排除集（{@code findRowIds} 逐个并集）；其余算子（GT/GE/LT/LE/BETWEEN/LIKE/IS_NULL…）→ 列值残差；
 * 无可用索引 → 分区 ids 全量扫描 + 列值残差。范围算子本轮**不走**内存 {@code rangeMaps}（其非持久化，
 * 进程重启即空会导致误返空集），统一走列值残差以保证正确性。</p>
 *
 * <p>
 * 语义口径：LIKE / LIKE_LEFT / LIKE_RIGHT 统一对齐 ORM（{@code contains / startsWith / endsWith}），
 * 修正既有 handler 的相反映射；输出恒 rowId 升序（R11）；先过滤后分页；命中 id 的 {@code getById}
 * 必非 null（R8 强一致）。</p>
 *
 * <p>
 * 只读无写盘（R9）、0 新线程（R10）、零实例状态（唯一私有构造）、仅依赖 JDK + first-party（R2）。</p>
 *
 * @author SP3.1 Implement Agent
 * @since P52 OQ12-㉚① 真·列扫描下推
 */
public final class ColumnScanPushdown {

    private ColumnScanPushdown() {
    }

    /**
     * 列扫描下推：谓词 → 命中 rowId（rowId 升序，已施加 offset/limit）。
     *
     * @param dbRoot          分区列存根目录（非 null）
     * @param entityClassName 实体类名（非空白）
     * @param query           谓词（可 null；null 或无条件 → 分区全量）
     * @param offset          偏移（负值按 0 处理）
     * @param limit           条数（≤0 按 100 处理）
     * @return 命中 rowId（非 null；升序）
     * @throws IllegalArgumentException dbRoot 为 null / entityClassName 空白 / 谓词非法
     */
    public static List<Long> scan(File dbRoot, String entityClassName, DbQuery query, int offset, int limit) {
        List<Long> hit = filter(dbRoot, entityClassName, query);
        int from = Math.min(Math.max(0, offset), hit.size());
        int to = Math.min(from + (limit <= 0 ? 100 : limit), hit.size());
        return new ArrayList<>(hit.subList(from, to));
    }

    /**
     * 谓词 → 命中 rowId 全量集合（rowId 升序，不分页）。
     *
     * <p>供 R12 等价性对照与分页复用；只读无写盘。</p>
     *
     * @param dbRoot          分区列存根目录（非 null）
     * @param entityClassName 实体类名（非空白）
     * @param query           谓词（可 null；null 或无条件 → 分区全量升序）
     * @return 命中 rowId（非 null；升序）
     * @throws IllegalArgumentException dbRoot 为 null / entityClassName 空白 / 谓词非法
     */
    public static List<Long> filter(File dbRoot, String entityClassName, DbQuery query) {
        requireDbRoot(dbRoot);
        requireEntity(entityClassName);
        ArrayList<Long> ids = loadPartitionIds(dbRoot, entityClassName);
        if (!DbQueryToQueryWrapper.hasPredicate(query)) {
            return ids;
        }
        validate(query);
        TableMetaStore.TableMeta meta = new TableMetaStore(dbRoot).getMeta(entityClassName);
        Map<String, TableMetaStore.ColumnDef> defs = buildColumnDefs(meta);
        try {
            ColumnarStore store = new ColumnarStore(dbRoot);
            EqIndexStore eqIndexStore = new EqIndexStore(dbRoot);
            HashSet<Long> idSet = new HashSet<>(ids);
            HashSet<Long> seen = new HashSet<>();
            ArrayList<Long> matched = new ArrayList<>();
            for (List<DbQueryCriterion> groupWhere : expandOrGroups(query)) {
                HashSet<Long> excluded =
                        findEqIndexExcludedRowIds(eqIndexStore, entityClassName, groupWhere, defs);
                long[] candidates =
                        findEqIndexCandidates(eqIndexStore, entityClassName, groupWhere, defs);
                if (candidates != null) {
                    for (long rowId : candidates) {
                        if (rowId <= 0L || !idSet.contains(rowId)) {
                            continue;
                        }
                        if (excluded != null && excluded.contains(rowId)) {
                            continue;
                        }
                        if (!matches(store, entityClassName, rowId, groupWhere, defs)) {
                            continue;
                        }
                        if (seen.add(rowId)) {
                            matched.add(rowId);
                        }
                    }
                    continue;
                }
                for (long rowId : ids) {
                    if (excluded != null && excluded.contains(rowId)) {
                        continue;
                    }
                    if (!matches(store, entityClassName, rowId, groupWhere, defs)) {
                        continue;
                    }
                    if (seen.add(rowId)) {
                        matched.add(rowId);
                    }
                }
            }
            matched.sort(null);
            return matched;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("column scan failed: " + entityClassName, e);
        }
    }

    /**
     * 读取分区 ids 集合（正 rowId，升序）。
     */
    private static ArrayList<Long> loadPartitionIds(File dbRoot, String entityClassName) {
        DsHashSet ids = new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, entityClassName));
        ArrayList<Long> out = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || id <= 0L) {
                continue;
            }
            out.add(id);
        }
        out.sort(null);
        return out;
    }

    /**
     * 谓词前置校验：null 条件 / op 空 / 列名空白 / {@code @composite:} 列 / IN·NOT_IN 缺 list。
     */
    private static void validate(DbQuery query) {
        checkCriteria(query == null ? null : query.where);
        if (query != null && query.anyOf != null) {
            for (DbQueryOrGroup g : query.anyOf) {
                if (g != null) {
                    checkCriteria(g.where);
                }
            }
        }
    }

    private static void checkCriteria(List<DbQueryCriterion> where) {
        if (where == null) {
            return;
        }
        for (int i = 0; i < where.size(); i++) {
            DbQueryCriterion c = where.get(i);
            if (c == null) {
                throw new IllegalArgumentException("where[" + i + "] criterion is null");
            }
            if (c.op == null) {
                throw new IllegalArgumentException("missing op");
            }
            if (c.name == null || c.name.isBlank()) {
                throw new IllegalArgumentException("missing name");
            }
            if (c.name.startsWith("@composite:")) {
                throw new IllegalArgumentException("composite query is not supported: " + c.name);
            }
            if ((c.op == DbQueryOp.IN || c.op == DbQueryOp.NOT_IN) && c.list == null) {
                throw new IllegalArgumentException(c.op + " requires list");
            }
        }
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

    /**
     * 由 {@link TableMetaStore.TableMeta} 构建「逻辑列名 → 列定义」映射（跳过空列名）。
     */
    public static Map<String, TableMetaStore.ColumnDef> buildColumnDefs(TableMetaStore.TableMeta meta) {
        HashMap<String, TableMetaStore.ColumnDef> out = new HashMap<>();
        if (meta == null || meta.columns == null) {
            return out;
        }
        for (TableMetaStore.ColumnDef c : meta.columns) {
            if (c == null || c.name == null || c.name.isBlank()) {
                continue;
            }
            out.put(c.name, c);
        }
        return out;
    }

    /**
     * {@code anyOf} OR 分组展开：每组 = 全局 where 与该组 where 的拼接（全局 ∩ 组）。
     */
    public static List<List<DbQueryCriterion>> expandOrGroups(DbQuery query) {
        List<DbQueryCriterion> global = query == null || query.where == null ? List.of() : query.where;
        if (query == null || query.anyOf == null || query.anyOf.isEmpty()) {
            return List.of(global);
        }
        ArrayList<List<DbQueryCriterion>> out = new ArrayList<>(query.anyOf.size());
        for (DbQueryOrGroup g : query.anyOf) {
            if (g == null || g.where == null || g.where.isEmpty()) {
                out.add(global);
                continue;
            }
            if (global.isEmpty()) {
                out.add(g.where);
                continue;
            }
            ArrayList<DbQueryCriterion> combined = new ArrayList<>(global.size() + g.where.size());
            combined.addAll(global);
            combined.addAll(g.where);
            out.add(combined);
        }
        return out;
    }

    /**
     * NOT_IN 排除集：对有索引的 NOT_IN 列，用 {@link EqIndexStore#findRowIds} 逐个取并集。
     *
     * @return 排除 rowId 集（无可用索引 → null）
     */
    public static HashSet<Long> findEqIndexExcludedRowIds(
            EqIndexStore eqIndexStore,
            String entityClassName,
            List<DbQueryCriterion> where,
            Map<String, TableMetaStore.ColumnDef> defs
    ) throws Exception {
        if (where == null || where.isEmpty()) {
            return null;
        }
        HashSet<Long> excluded = null;
        for (DbQueryCriterion c : where) {
            if (c == null || c.op != DbQueryOp.NOT_IN) {
                continue;
            }
            if (c.name == null || c.name.isBlank() || c.name.startsWith("@composite:")) {
                continue;
            }
            if (c.list == null || c.list.isEmpty()) {
                continue;
            }
            if (!eqIndexStore.exists(entityClassName, c.name)) {
                continue;
            }
            EqIndexMetaStore.IndexDef idx = eqIndexStore.get(entityClassName, c.name);
            if (idx == null || idx.colId <= 0L) {
                continue;
            }
            TableMetaStore.ColumnDef def = defs.get(c.name);
            if (def == null) {
                continue;
            }
            if (excluded == null) {
                excluded = new HashSet<>();
            }
            for (String s : c.list) {
                if (s == null) {
                    continue;
                }
                byte[] valueBytes = encodeForEqIndex(def, s);
                long[] ids = eqIndexStore.findRowIds(entityClassName, idx.colId, valueBytes, 0);
                if (ids == null) {
                    continue;
                }
                for (long rowId : ids) {
                    excluded.add(rowId);
                }
            }
        }
        return excluded == null || excluded.isEmpty() ? null : excluded;
    }

    /**
     * EQ/IN 候选集：对有索引的 EQ/IN 列取 {@link EqIndexStore#findRowIds}，多列时取交集。
     *
     * @return 候选 rowId（无可用索引 → null）
     */
    public static long[] findEqIndexCandidates(
            EqIndexStore eqIndexStore,
            String entityClassName,
            List<DbQueryCriterion> where,
            Map<String, TableMetaStore.ColumnDef> defs
    ) throws Exception {
        if (where == null || where.isEmpty()) {
            return null;
        }
        ArrayList<long[]> sets = new ArrayList<>();
        for (DbQueryCriterion c : where) {
            if (c == null || (c.op != DbQueryOp.EQ && c.op != DbQueryOp.IN)) {
                continue;
            }
            if (c.name == null || c.name.isBlank() || c.name.startsWith("@composite:")) {
                continue;
            }
            if (!eqIndexStore.exists(entityClassName, c.name)) {
                continue;
            }
            EqIndexMetaStore.IndexDef idx = eqIndexStore.get(entityClassName, c.name);
            if (idx == null || idx.colId <= 0L) {
                continue;
            }
            TableMetaStore.ColumnDef def = defs.get(c.name);
            if (def == null) {
                continue;
            }
            if (c.op == DbQueryOp.EQ) {
                if (c.a == null) {
                    continue;
                }
                byte[] valueBytes = encodeForEqIndex(def, c.a);
                long[] ids = eqIndexStore.findRowIds(entityClassName, idx.colId, valueBytes, 0);
                sets.add(ids == null ? new long[0] : ids);
                continue;
            }
            if (c.list == null || c.list.isEmpty()) {
                sets.add(new long[0]);
                continue;
            }
            HashSet<Long> union = new HashSet<>();
            for (String s : c.list) {
                if (s == null) {
                    continue;
                }
                byte[] valueBytes = encodeForEqIndex(def, s);
                long[] ids = eqIndexStore.findRowIds(entityClassName, idx.colId, valueBytes, 0);
                if (ids == null) {
                    continue;
                }
                for (long rowId : ids) {
                    union.add(rowId);
                }
            }
            long[] ids = new long[union.size()];
            int p = 0;
            for (Long v : union) {
                if (v == null) {
                    continue;
                }
                ids[p++] = v;
            }
            sets.add(p == ids.length ? ids : Arrays.copyOf(ids, p));
        }
        if (sets.isEmpty()) {
            return null;
        }
        if (sets.size() == 1) {
            return sets.get(0);
        }
        sets.sort(Comparator.comparingInt(a -> a.length));
        long[] base = sets.get(0);
        for (int i = 1; i < sets.size(); i++) {
            if (base.length == 0) {
                return base;
            }
            long[] cur = sets.get(i);
            HashSet<Long> curSet = new HashSet<>(Math.max(16, cur.length * 2));
            for (long v : cur) {
                curSet.add(v);
            }
            long[] tmp = new long[Math.min(base.length, cur.length)];
            int p = 0;
            for (long v : base) {
                if (curSet.contains(v)) {
                    tmp[p++] = v;
                }
            }
            base = p == tmp.length ? tmp : Arrays.copyOf(tmp, p);
        }
        return base;
    }

    /**
     * 把查询值文本编码为列索引键（与列存落盘字节一致）：int/long/boolean/定长字符串。
     */
    private static byte[] encodeForEqIndex(TableMetaStore.ColumnDef def, String text) {
        String t = def == null || def.javaType == null ? "" : def.javaType;
        if ("int".equals(t) || "java.lang.Integer".equals(t)) {
            int v = Integer.parseInt(text);
            return ByteBuffer.allocate(4).putInt(v).array();
        }
        if ("long".equals(t) || "java.lang.Long".equals(t)) {
            long v = Long.parseLong(text);
            return ByteBuffer.allocate(8).putLong(v).array();
        }
        if ("boolean".equals(t) || "java.lang.Boolean".equals(t)) {
            return new byte[]{(byte) (Boolean.parseBoolean(text) ? 1 : 0)};
        }
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        int expectedLen = def == null ? 0 : def.length;
        if (expectedLen <= 0 || raw.length >= expectedLen) {
            return raw;
        }
        byte[] padded = new byte[expectedLen];
        System.arraycopy(raw, 0, padded, 0, raw.length);
        return padded;
    }

    /**
     * 逐条件残余校验（列值级）：全部条件命中才为 true。
     */
    public static boolean matches(
            ColumnarStore store,
            String entityClassName,
            long rowId,
            List<DbQueryCriterion> where,
            Map<String, TableMetaStore.ColumnDef> defs
    ) throws Exception {
        if (where == null || where.isEmpty()) {
            return true;
        }
        for (DbQueryCriterion c : where) {
            if (c == null) {
                throw new IllegalArgumentException("null criterion");
            }
            if (c.op == null) {
                throw new IllegalArgumentException("missing op");
            }
            if (c.name == null || c.name.isBlank()) {
                throw new IllegalArgumentException("missing name");
            }
            if (!matchesOne(store, entityClassName, rowId, c, defs)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesOne(
            ColumnarStore store,
            String entityClassName,
            long rowId,
            DbQueryCriterion c,
            Map<String, TableMetaStore.ColumnDef> defs
    ) throws Exception {
        if (c.name.startsWith("@composite:")) {
            throw new IllegalArgumentException("composite query is not supported: " + c.name);
        }
        TableMetaStore.ColumnDef def = defs.get(c.name);
        if (def == null) {
            throw new IllegalArgumentException("unknown column: " + c.name);
        }
        byte[] bytes = store.getValue(entityClassName, c.name, rowId);
        boolean isNull = bytes == null;
        DbQueryOp op = c.op;
        if (op == DbQueryOp.IS_NULL) {
            return isNull;
        }
        if (op == DbQueryOp.IS_NOT_NULL) {
            return !isNull;
        }
        if (isNull) {
            return false;
        }
        if ((op == DbQueryOp.IN || op == DbQueryOp.NOT_IN) && c.list == null) {
            throw new IllegalArgumentException(op + " requires list");
        }

        Object left = decode(def.javaType, bytes);
        Object a = c.a == null ? null : parse(def.javaType, c.a);
        Object b = c.b == null ? null : parse(def.javaType, c.b);
        List<Object> list = List.of();
        if (op == DbQueryOp.IN || op == DbQueryOp.NOT_IN) {
            ArrayList<Object> out = new ArrayList<>();
            for (String s : c.list) {
                if (s == null) {
                    continue;
                }
                out.add(parse(def.javaType, s));
            }
            list = out;
        }
        return eval(op, left, a, b, list);
    }

    /**
     * 列值字节 → 类型化对象（int/long/boolean/字符串）。
     */
    public static Object decode(String javaType, byte[] bytes) {
        String t = javaType == null ? "" : javaType;
        if ("int".equals(t) || "java.lang.Integer".equals(t)) {
            if (bytes.length < 4) {
                return 0;
            }
            return ByteBuffer.wrap(bytes).getInt();
        }
        if ("long".equals(t) || "java.lang.Long".equals(t)) {
            if (bytes.length < 8) {
                return 0L;
            }
            return ByteBuffer.wrap(bytes).getLong();
        }
        if ("boolean".equals(t) || "java.lang.Boolean".equals(t)) {
            return bytes.length > 0 && bytes[0] != 0;
        }
        return decodeString(bytes);
    }

    private static String decodeString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) {
            end--;
        }
        if (end <= 0) {
            return "";
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static Object parse(String javaType, String text) {
        String t = javaType == null ? "" : javaType;
        if ("int".equals(t) || "java.lang.Integer".equals(t)) {
            return Integer.parseInt(text);
        }
        if ("long".equals(t) || "java.lang.Long".equals(t)) {
            return Long.parseLong(text);
        }
        if ("boolean".equals(t) || "java.lang.Boolean".equals(t)) {
            return Boolean.parseBoolean(text);
        }
        return text;
    }

    /**
     * 单算子比较（LIKE 系列对齐 ORM：contains / startsWith / endsWith）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean eval(DbQueryOp op, Object left, Object a, Object b, List<Object> list) {
        if (op == DbQueryOp.EQ) {
            return Objects.equals(left, a);
        }
        if (op == DbQueryOp.NE) {
            return !Objects.equals(left, a);
        }
        if (left instanceof Comparable cmpLeft) {
            if (op == DbQueryOp.GT) {
                return cmpLeft.compareTo(a) > 0;
            }
            if (op == DbQueryOp.GE) {
                return cmpLeft.compareTo(a) >= 0;
            }
            if (op == DbQueryOp.LT) {
                return cmpLeft.compareTo(a) < 0;
            }
            if (op == DbQueryOp.LE) {
                return cmpLeft.compareTo(a) <= 0;
            }
            if (op == DbQueryOp.BETWEEN) {
                return cmpLeft.compareTo(a) >= 0 && cmpLeft.compareTo(b) <= 0;
            }
        }
        if (op == DbQueryOp.IN) {
            return list.contains(left);
        }
        if (op == DbQueryOp.NOT_IN) {
            return !list.contains(left);
        }
        if (left instanceof String s) {
            String q = a == null ? "" : String.valueOf(a);
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
}
