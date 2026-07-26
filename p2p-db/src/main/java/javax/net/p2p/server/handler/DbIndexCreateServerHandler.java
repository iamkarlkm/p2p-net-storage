package javax.net.p2p.server.handler;

import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexStore;
import com.q3lives.ds.database.schema.DynamicIndexUtil;
import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbIndexCreateRequest;
import javax.net.p2p.model.DbIndexCreateResponse;
import javax.net.p2p.model.P2PWrapper;

public class DbIndexCreateServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_INDEX_CREATE;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_INDEX_CREATE) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbIndexCreateRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName");
            }
            if (payload.logicalName == null || payload.logicalName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing logicalName");
            }
            if (payload.logicalName.startsWith("@composite:")) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "composite index is not supported");
            }

            DsDatabaseLocal db = DsDatabaseLocal.load();
            File dbRoot = db.getRoot();
            TableMetaStore metaStore = new TableMetaStore(dbRoot);
            TableMetaStore.TableMeta meta = metaStore.getMeta(payload.entityClassName);
            if (meta.signature == null || meta.signature.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing table meta");
            }

            TableMetaStore.ColumnDef def = null;
            if (meta.columns != null) {
                for (TableMetaStore.ColumnDef c : meta.columns) {
                    if (c != null && payload.logicalName.equals(c.name)) {
                        def = c;
                        break;
                    }
                }
            }
            if (def == null || def.colId <= 0L) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "unknown column");
            }

            EqIndexStore idx = new EqIndexStore(dbRoot);
            idx.createOrReplace(payload.entityClassName, payload.logicalName, def.colId);

            DsHashSet ids = new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, payload.entityClassName));
            ColumnarStore store = new ColumnarStore(dbRoot);
            for (Long rowIdObj : ids) {
                if (rowIdObj == null) {
                    continue;
                }
                long rowId = rowIdObj;
                if (rowId <= 0L) {
                    continue;
                }
                byte[] bytes = store.getValue(payload.entityClassName, payload.logicalName, rowId);
                if (bytes == null) {
                    continue;
                }
                idx.add(payload.entityClassName, def.colId, bytes, rowId);
            }

            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_INDEX_CREATE, new DbIndexCreateResponse(true));
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}

