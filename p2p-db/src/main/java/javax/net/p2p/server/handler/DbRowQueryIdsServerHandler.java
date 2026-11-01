package javax.net.p2p.server.handler;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexStore;
import com.q3lives.ds.database.columnar.pushdown.ColumnScanPushdown;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOrder;
import javax.net.p2p.model.DbRowQueryIdsRequest;
import javax.net.p2p.model.DbRowQueryIdsResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;

/**
 * 行存 id 查询处理器。
 *
 * <p>
 * P52 OQ12-㉚①：过滤段（{@code expandOrGroups} / {@code findEqIndexCandidates} /
 * {@code findEqIndexExcludedRowIds} / {@code matches}）等价搬迁至共享列扫描器
 * {@link ColumnScanPushdown}；本类的同名静态方法保留为一行委派，供
 * {@code DbRowCountServerHandler} / {@code DbRowExistsByQueryServerHandler} /
 * {@code DbRowQueryIdsStreamServerHandler} 复用，避免算法副本造成 CPD 重复块。
 * 本类自持 {@code ORDER BY} 排序与分页逻辑。</p>
 */
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

            // P52 列扫描下推：过滤段委派共享列扫描器（等价搬迁，输出恒 rowId 升序）。
            List<Long> matched = ColumnScanPushdown.filter(dbRoot, payload.entityClassName, query);

            if (query.orderBy != null && !query.orderBy.isEmpty() && !matched.isEmpty()) {
                ColumnarStore store = new ColumnarStore(dbRoot);
                Map<String, TableMetaStore.ColumnDef> defs = buildColumnDefs(meta);
                List<DbQueryOrder> orders = query.orderBy;
                ArrayList<RowSortKey> keys = new ArrayList<>(matched.size());
                for (long rowId : matched) {
                    keys.add(buildRowSortKey(store, payload.entityClassName, rowId, orders, defs));
                }
                keys.sort(buildComparator(orders));
                matched = new ArrayList<>(keys.size());
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

    static Map<String, TableMetaStore.ColumnDef> buildColumnDefs(TableMetaStore.TableMeta meta) {
        return ColumnScanPushdown.buildColumnDefs(meta);
    }

    static List<List<DbQueryCriterion>> expandOrGroups(DbQuery query) {
        return ColumnScanPushdown.expandOrGroups(query);
    }

    static HashSet<Long> findEqIndexExcludedRowIds(
        EqIndexStore eqIndexStore,
        String entityClassName,
        List<DbQueryCriterion> where,
        Map<String, TableMetaStore.ColumnDef> defs
    ) throws Exception {
        return ColumnScanPushdown.findEqIndexExcludedRowIds(eqIndexStore, entityClassName, where, defs);
    }

    static long[] findEqIndexCandidates(
        EqIndexStore eqIndexStore,
        String entityClassName,
        List<DbQueryCriterion> where,
        Map<String, TableMetaStore.ColumnDef> defs
    ) throws Exception {
        return ColumnScanPushdown.findEqIndexCandidates(eqIndexStore, entityClassName, where, defs);
    }

    static boolean matches(
        ColumnarStore store,
        String entityClassName,
        long rowId,
        List<DbQueryCriterion> where,
        Map<String, TableMetaStore.ColumnDef> defs
    ) throws Exception {
        return ColumnScanPushdown.matches(store, entityClassName, rowId, where, defs);
    }

    static RowSortKey buildRowSortKey(
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
            keys[i] = bytes == null ? null : ColumnScanPushdown.decode(def.javaType, bytes);
        }
        return new RowSortKey(rowId, keys);
    }

    static Comparator<RowSortKey> buildComparator(List<DbQueryOrder> orders) {
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

    static final class RowSortKey {
        final long rowId;
        final Object[] keys;

        RowSortKey(long rowId, Object[] keys) {
            this.rowId = rowId;
            this.keys = keys;
        }
    }
}
