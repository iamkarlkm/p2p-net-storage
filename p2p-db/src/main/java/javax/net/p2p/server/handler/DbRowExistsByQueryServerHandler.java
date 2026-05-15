package javax.net.p2p.server.handler;

import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexStore;
import com.q3lives.ds.database.schema.DynamicIndexUtil;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbRowExistsByQueryRequest;
import javax.net.p2p.model.DbRowExistsByQueryResponse;
import javax.net.p2p.model.P2PWrapper;

public class DbRowExistsByQueryServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_ROW_EXISTS_BY_QUERY;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_ROW_EXISTS_BY_QUERY) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbRowExistsByQueryRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName");
            }
            DbQuery query = payload.query == null ? new DbQuery() : payload.query;

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
            Map<String, TableMetaStore.ColumnDef> defs = DbRowQueryIdsServerHandler.buildColumnDefs(meta);

            List<List<DbQueryCriterion>> groups = DbRowQueryIdsServerHandler.expandOrGroups(query);
            HashSet<Long> seen = new HashSet<>();
            for (List<DbQueryCriterion> groupWhere : groups) {
                HashSet<Long> excluded = DbRowQueryIdsServerHandler.findEqIndexExcludedRowIds(
                    eqIndexStore, payload.entityClassName, groupWhere, defs
                );
                long[] candidates = DbRowQueryIdsServerHandler.findEqIndexCandidates(
                    eqIndexStore, payload.entityClassName, groupWhere, defs
                );
                if (candidates != null) {
                    for (long rowId : candidates) {
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
                        if (DbRowQueryIdsServerHandler.matches(store, payload.entityClassName, rowId, groupWhere, defs)) {
                            return P2PWrapper.build(
                                request.getSeq(),
                                P2PCommand.R_OK_DB_ROW_EXISTS_BY_QUERY,
                                new DbRowExistsByQueryResponse(true)
                            );
                        }
                    }
                    continue;
                }
                for (Long rowIdObj : ids) {
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
                    if (DbRowQueryIdsServerHandler.matches(store, payload.entityClassName, rowId, groupWhere, defs)) {
                        return P2PWrapper.build(
                            request.getSeq(),
                            P2PCommand.R_OK_DB_ROW_EXISTS_BY_QUERY,
                            new DbRowExistsByQueryResponse(true)
                        );
                    }
                }
            }

            return P2PWrapper.build(
                request.getSeq(),
                P2PCommand.R_OK_DB_ROW_EXISTS_BY_QUERY,
                new DbRowExistsByQueryResponse(false)
            );
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
