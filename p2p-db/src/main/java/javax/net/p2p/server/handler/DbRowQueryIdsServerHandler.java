package javax.net.p2p.server.handler;

import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexStore;
import com.q3lives.ds.database.schema.DynamicIndexUtil;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;
import javax.net.p2p.model.DbQueryOrder;
import javax.net.p2p.model.DbRowQueryIdsRequest;
import javax.net.p2p.model.DbRowQueryIdsResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;

public class DbRowQueryIdsServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_ROW_QUERY_IDS;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_ROW_QUERY_IDS) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbRowQueryIdsRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName");
            }
            DbQuery query = payload.query == null ? new DbQuery() : payload.query;
            int offset = Math.max(0, payload.offset);
            int limit = payload.limit <= 0 ? 100 : payload.limit;

            DsDatabaseLocal db = DsDatabaseLocal.load();
            File dbRoot = db.getRoot();

            TableMetaStore metaStore = new TableMetaStore(dbRoot);
            TableMetaStore.TableMeta meta = metaStore.getMeta(payload.entityClassName);
            if (meta.signature == null || meta.signature.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing table meta");
            }

            DsHashSet ids = new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, payload.entityClassName));
            ColumnarStore store = new ColumnarStore(dbRoot);
            EqIndexStore eqIndexStore = new EqIndexStore(dbRoot);

            Map<String, TableMetaStore.ColumnDef> defs = buildColumnDefs(meta);
            ArrayList<Long> matched = new ArrayList<>();
            long[] candidates = findEqIndexCandidates(eqIndexStore, payload.entityClassName, query.where, defs);
            if (candidates != null) {
                HashSet<Long> seen = new HashSet<>();
                for (long rowId : candidates) {
                    if (rowId <= 0L) {
                        continue;
                    }
                    if (!ids.contains(rowId)) {
                        continue;
                    }
                    if (!seen.add(rowId)) {
                        continue;
                    }
                    if (matches(store, payload.entityClassName, rowId, query.where, defs)) {
                        matched.add(rowId);
                    }
                }
            } else {
                for (Long rowIdObj : ids) {
                    if (rowIdObj == null) {
                        continue;
                    }
                    long rowId = rowIdObj;
                    if (rowId <= 0L) {
                        continue;
                    }
                    if (matches(store, payload.entityClassName, rowId, query.where, defs)) {
                        matched.add(rowId);
                    }
                }
            }

            if (query.orderBy != null && !query.orderBy.isEmpty() && !matched.isEmpty()) {
                List<DbQueryOrder> orders = query.orderBy;
                ArrayList<RowSortKey> keys = new ArrayList<>(matched.size());
                for (long rowId : matched) {
                    keys.add(buildRowSortKey(store, payload.entityClassName, rowId, orders, defs));
                }
                keys.sort(buildComparator(orders));
                matched.clear();
                for (RowSortKey k : keys) {
                    matched.add(k.rowId);
                }
            }

            int from = Math.min(offset, matched.size());
            int to = Math.min(from + limit, matched.size());
            long[] out = new long[to - from];
            for (int i = from; i < to; i++) {
                out[i - from] = matched.get(i);
            }
            byte[] idsBytes = SerializationUtil.serialize(out);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_ROW_QUERY_IDS, new DbRowQueryIdsResponse(idsBytes));
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }

    private static Map<String, TableMetaStore.ColumnDef> buildColumnDefs(TableMetaStore.TableMeta meta) {
        HashMap<String, TableMetaStore.ColumnDef> out = new HashMap<>();
        if (meta.columns == null) {
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

    private static long[] findEqIndexCandidates(
        EqIndexStore eqIndexStore,
        String entityClassName,
        List<DbQueryCriterion> where,
        Map<String, TableMetaStore.ColumnDef> defs
    ) throws Exception {
        if (where == null || where.isEmpty()) {
            return null;
        }
        for (DbQueryCriterion c : where) {
            if (c == null || c.op != DbQueryOp.EQ) {
                continue;
            }
            if (c.name == null || c.name.isBlank() || c.name.startsWith("@composite:")) {
                continue;
            }
            if (c.a == null) {
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
            byte[] valueBytes = encodeForEqIndex(def, c.a);
            return eqIndexStore.findRowIds(entityClassName, idx.colId, valueBytes, 0);
        }
        return null;
    }

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

    private static boolean matches(
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
                continue;
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

        Object left = decode(def.javaType, bytes);
        Object a = c.a == null ? null : parse(def.javaType, c.a);
        Object b = c.b == null ? null : parse(def.javaType, c.b);
        List<Object> list = new ArrayList<>();
        if (c.list != null) {
            for (String s : c.list) {
                if (s == null) {
                    continue;
                }
                list.add(parse(def.javaType, s));
            }
        }
        return eval(op, left, a, b, list);
    }

    private static Object decode(String javaType, byte[] bytes) {
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
                return s.endsWith(q);
            }
            if (op == DbQueryOp.LIKE_RIGHT) {
                return s.startsWith(q);
            }
        }
        return false;
    }

    private static RowSortKey buildRowSortKey(
        ColumnarStore store,
        String entityClassName,
        long rowId,
        List<DbQueryOrder> orders,
        Map<String, TableMetaStore.ColumnDef> defs
    ) throws Exception {
        Object[] keys = new Object[orders.size()];
        for (int i = 0; i < orders.size(); i++) {
            DbQueryOrder o = orders.get(i);
            if (o == null || o.name == null || o.name.isBlank()) {
                keys[i] = null;
                continue;
            }
            if (o.name.startsWith("@composite:")) {
                keys[i] = null;
                continue;
            }
            TableMetaStore.ColumnDef def = defs.get(o.name);
            if (def == null) {
                keys[i] = null;
                continue;
            }
            byte[] bytes = store.getValue(entityClassName, o.name, rowId);
            keys[i] = bytes == null ? null : decode(def.javaType, bytes);
        }
        return new RowSortKey(rowId, keys);
    }

    private static Comparator<RowSortKey> buildComparator(List<DbQueryOrder> orders) {
        return (a, b) -> {
            for (int i = 0; i < orders.size(); i++) {
                DbQueryOrder o = orders.get(i);
                Object ka = a.keys[i];
                Object kb = b.keys[i];
                int cmp = compareNullable(ka, kb);
                if (cmp != 0) {
                    return (o != null && !o.asc) ? -cmp : cmp;
                }
            }
            return Long.compare(a.rowId, b.rowId);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareNullable(Object a, Object b) {
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

    private static final class RowSortKey {
        final long rowId;
        final Object[] keys;

        RowSortKey(long rowId, Object[] keys) {
            this.rowId = rowId;
            this.keys = keys;
        }
    }
}
