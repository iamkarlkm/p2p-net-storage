package javax.net.p2p.server.handler;

import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexStore;
import com.q3lives.ds.database.schema.DynamicIndexUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbRowQueryIdsResponse;
import javax.net.p2p.model.DbRowQueryIdsStreamRequest;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.utils.SerializationUtil;

public class DbRowQueryIdsStreamServerHandler extends AbstractStreamRequestAdapter implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_ROW_QUERY_IDS_STREAM;
    }

    @Override
    public void processStream(AbstractSendMesageExecutor executor, P2PWrapper request) throws InterruptedException {
        try {
            if (request.getCommand() != P2PCommand.DB_ROW_QUERY_IDS_STREAM) {
                executor.sendResponse(P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH));
                return;
            }
            if (!(request.getData() instanceof DbRowQueryIdsStreamRequest payload)) {
                executor.sendResponse(P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload"));
                return;
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                executor.sendResponse(P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName"));
                return;
            }
            DbQuery query = payload.query == null ? new DbQuery() : payload.query;
            int offset = Math.max(0, payload.offset);
            int limit = payload.limit <= 0 ? Integer.MAX_VALUE : payload.limit;
            int chunkSize = payload.chunkSize <= 0 ? 256 : Math.min(payload.chunkSize, 4096);

            DsDatabaseLocal db = DsDatabaseLocal.load();
            File dbRoot = db.getRoot();

            TableMetaStore metaStore = new TableMetaStore(dbRoot);
            TableMetaStore.TableMeta meta = metaStore.getMeta(payload.entityClassName);
            if (meta.signature == null || meta.signature.isBlank()) {
                executor.sendResponse(P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing table meta"));
                return;
            }

            DsHashSet ids = new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, payload.entityClassName));
            ColumnarStore store = new ColumnarStore(dbRoot);
            EqIndexStore eqIndexStore = new EqIndexStore(dbRoot);
            Map<String, TableMetaStore.ColumnDef> defs = DbRowQueryIdsServerHandler.buildColumnDefs(meta);

            if (query.orderBy != null && !query.orderBy.isEmpty()) {
                streamOrderByTopK(
                    executor,
                    request.getSeq(),
                    payload.entityClassName,
                    query,
                    ids,
                    store,
                    eqIndexStore,
                    defs,
                    offset,
                    payload.limit,
                    chunkSize
                );
                return;
            }

            streamUnordered(
                executor,
                request.getSeq(),
                payload.entityClassName,
                query,
                ids,
                store,
                eqIndexStore,
                defs,
                offset,
                limit,
                chunkSize
            );
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            executor.sendResponse(P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString()));
        }
    }

    private void streamUnordered(
        AbstractSendMesageExecutor executor,
        int seq,
        String entityClassName,
        DbQuery query,
        DsHashSet ids,
        ColumnarStore store,
        EqIndexStore eqIndexStore,
        Map<String, TableMetaStore.ColumnDef> defs,
        int offset,
        int limit,
        int chunkSize
    ) throws Exception {
        long skipped = 0L;
        long emitted = 0L;
        int chunkIndex = 0;
        int p = 0;
        long[] buf = new long[chunkSize];

        List<List<DbQueryCriterion>> groups = DbRowQueryIdsServerHandler.expandOrGroups(query);
        HashSet<Long> seen = new HashSet<>();
        for (List<DbQueryCriterion> groupWhere : groups) {
            HashSet<Long> excluded = DbRowQueryIdsServerHandler.findEqIndexExcludedRowIds(eqIndexStore, entityClassName, groupWhere, defs);
            long[] candidates = DbRowQueryIdsServerHandler.findEqIndexCandidates(eqIndexStore, entityClassName, groupWhere, defs);
            if (candidates != null) {
                for (long rowId : candidates) {
                    if (!continued) {
                        return;
                    }
                    if (rowId <= 0L) {
                        continue;
                    }
                    if (excluded != null && excluded.contains(rowId)) {
                        continue;
                    }
                    if (!ids.contains(rowId)) {
                        continue;
                    }
                    if (!seen.add(rowId)) {
                        continue;
                    }
                    if (!DbRowQueryIdsServerHandler.matches(store, entityClassName, rowId, groupWhere, defs)) {
                        continue;
                    }
                    if (skipped < offset) {
                        skipped++;
                        continue;
                    }
                    buf[p++] = rowId;
                    emitted++;
                    if (p == buf.length) {
                        sendChunk(executor, seq, chunkIndex++, buf, p, false);
                        p = 0;
                    }
                    if (emitted >= limit) {
                        sendChunk(executor, seq, chunkIndex, buf, p, true);
                        return;
                    }
                }
                continue;
            }
            for (Long rowIdObj : ids) {
                if (!continued) {
                    return;
                }
                if (rowIdObj == null) {
                    continue;
                }
                long rowId = rowIdObj;
                if (rowId <= 0L) {
                    continue;
                }
                if (excluded != null && excluded.contains(rowId)) {
                    continue;
                }
                if (!seen.add(rowId)) {
                    continue;
                }
                if (!DbRowQueryIdsServerHandler.matches(store, entityClassName, rowId, groupWhere, defs)) {
                    continue;
                }
                if (skipped < offset) {
                    skipped++;
                    continue;
                }
                buf[p++] = rowId;
                emitted++;
                if (p == buf.length) {
                    sendChunk(executor, seq, chunkIndex++, buf, p, false);
                    p = 0;
                }
                if (emitted >= limit) {
                    sendChunk(executor, seq, chunkIndex, buf, p, true);
                    return;
                }
            }
        }
        sendChunk(executor, seq, chunkIndex, buf, p, true);
    }

    private void streamOrderByTopK(
        AbstractSendMesageExecutor executor,
        int seq,
        String entityClassName,
        DbQuery query,
        DsHashSet ids,
        ColumnarStore store,
        EqIndexStore eqIndexStore,
        Map<String, TableMetaStore.ColumnDef> defs,
        int offset,
        int limit,
        int chunkSize
    ) throws Exception {
        if (limit <= 0) {
            executor.sendResponse(P2PErrors.stdError(seq, P2PErrorCode.INVALID_REQUEST, "orderBy requires a positive limit in stream"));
            return;
        }
        long keepLong = (long) offset + (long) limit;
        int maxKeep = 20_000;
        if (keepLong > maxKeep) {
            executor.sendResponse(P2PErrors.stdError(seq, P2PErrorCode.INVALID_REQUEST, "orderBy stream limit too large: offset+limit > " + maxKeep));
            return;
        }
        int keep = (int) keepLong;

        Comparator<DbRowQueryIdsServerHandler.RowSortKey> cmp = DbRowQueryIdsServerHandler.buildComparator(query.orderBy);
        PriorityQueue<DbRowQueryIdsServerHandler.RowSortKey> pq = new PriorityQueue<>(keep + 1, cmp.reversed());

        List<List<DbQueryCriterion>> groups = DbRowQueryIdsServerHandler.expandOrGroups(query);
        HashSet<Long> seen = new HashSet<>();
        for (List<DbQueryCriterion> groupWhere : groups) {
            HashSet<Long> excluded = DbRowQueryIdsServerHandler.findEqIndexExcludedRowIds(eqIndexStore, entityClassName, groupWhere, defs);
            long[] candidates = DbRowQueryIdsServerHandler.findEqIndexCandidates(eqIndexStore, entityClassName, groupWhere, defs);
            if (candidates != null) {
                for (long rowId : candidates) {
                    if (!continued) {
                        return;
                    }
                    if (rowId <= 0L) {
                        continue;
                    }
                    if (excluded != null && excluded.contains(rowId)) {
                        continue;
                    }
                    if (!ids.contains(rowId)) {
                        continue;
                    }
                    if (!seen.add(rowId)) {
                        continue;
                    }
                    if (!DbRowQueryIdsServerHandler.matches(store, entityClassName, rowId, groupWhere, defs)) {
                        continue;
                    }
                    offerTopK(pq, keep, DbRowQueryIdsServerHandler.buildRowSortKey(store, entityClassName, rowId, query.orderBy, defs), cmp);
                }
                continue;
            }
            for (Long rowIdObj : ids) {
                if (!continued) {
                    return;
                }
                if (rowIdObj == null) {
                    continue;
                }
                long rowId = rowIdObj;
                if (rowId <= 0L) {
                    continue;
                }
                if (excluded != null && excluded.contains(rowId)) {
                    continue;
                }
                if (!seen.add(rowId)) {
                    continue;
                }
                if (!DbRowQueryIdsServerHandler.matches(store, entityClassName, rowId, groupWhere, defs)) {
                    continue;
                }
                offerTopK(pq, keep, DbRowQueryIdsServerHandler.buildRowSortKey(store, entityClassName, rowId, query.orderBy, defs), cmp);
            }
        }

        ArrayList<DbRowQueryIdsServerHandler.RowSortKey> keys = new ArrayList<>(pq);
        keys.sort(cmp);

        int from = Math.min(offset, keys.size());
        int to = Math.min(from + limit, keys.size());
        int chunkIndex = 0;
        int p = 0;
        long[] buf = new long[chunkSize];
        for (int i = from; i < to; i++) {
            if (!continued) {
                return;
            }
            buf[p++] = keys.get(i).rowId;
            if (p == buf.length) {
                sendChunk(executor, seq, chunkIndex++, buf, p, false);
                p = 0;
            }
        }
        sendChunk(executor, seq, chunkIndex, buf, p, true);
    }

    private static void offerTopK(
        PriorityQueue<DbRowQueryIdsServerHandler.RowSortKey> pq,
        int keep,
        DbRowQueryIdsServerHandler.RowSortKey key,
        Comparator<DbRowQueryIdsServerHandler.RowSortKey> cmp
    ) {
        if (keep <= 0) {
            return;
        }
        if (pq.size() < keep) {
            pq.offer(key);
            return;
        }
        DbRowQueryIdsServerHandler.RowSortKey worst = pq.peek();
        if (worst != null && cmp.compare(key, worst) < 0) {
            pq.poll();
            pq.offer(key);
        }
    }

    private static void sendChunk(
        AbstractSendMesageExecutor executor,
        int seq,
        int chunkIndex,
        long[] buf,
        int size,
        boolean completed
    ) throws InterruptedException {
        long[] out = size <= 0 ? new long[0] : new long[size];
        if (size > 0) {
            System.arraycopy(buf, 0, out, 0, size);
        }
        byte[] idsBytes = SerializationUtil.serialize(out);
        DbRowQueryIdsResponse payload = new DbRowQueryIdsResponse(idsBytes);
        executor.sendResponse(StreamP2PWrapper.buildStream(seq, chunkIndex, P2PCommand.R_OK_DB_ROW_QUERY_IDS_STREAM, payload, completed));
    }
}
