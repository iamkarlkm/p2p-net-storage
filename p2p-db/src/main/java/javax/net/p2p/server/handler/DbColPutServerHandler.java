package javax.net.p2p.server.handler;

import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.index.EqIndexStore;
import com.q3lives.ds.database.schema.DynamicIndexUtil;
import java.io.File;
import java.util.Arrays;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbColPutRequest;
import javax.net.p2p.model.DbColPutResponse;
import javax.net.p2p.model.P2PWrapper;

public class DbColPutServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_COL_PUT;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_COL_PUT) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbColPutRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName");
            }
            if (payload.logicalName == null || payload.logicalName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing logicalName");
            }
            if (payload.rowId <= 0L) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid rowId");
            }

            DsDatabaseLocal db = DsDatabaseLocal.load();
            File dbRoot = db.getRoot();
            DsHashSet ids = new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, payload.entityClassName));
            if (!ids.contains(payload.rowId) && !payload.upsertRow) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "row not exists");
            }
            if (payload.upsertRow) {
                ids.add(payload.rowId);
            }

            ColumnarStore store = new ColumnarStore(dbRoot);
            EqIndexStore eqIndexStore = new EqIndexStore(dbRoot);

            byte[] oldBytes = null;
            boolean hasEqIndex = eqIndexStore.exists(payload.entityClassName, payload.logicalName);
            if (hasEqIndex) {
                oldBytes = store.getValue(payload.entityClassName, payload.logicalName, payload.rowId);
            }

            long valueId = store.putValue(payload.entityClassName, payload.logicalName, payload.rowId, payload.valueBytes);
            if (hasEqIndex) {
                byte[] storedBytes = store.getValue(payload.entityClassName, payload.logicalName, payload.rowId);
                if (!Arrays.equals(oldBytes, storedBytes)) {
                    var idx = eqIndexStore.get(payload.entityClassName, payload.logicalName);
                    if (idx != null && idx.colId > 0L) {
                        if (oldBytes != null) {
                            eqIndexStore.remove(payload.entityClassName, idx.colId, oldBytes, payload.rowId);
                        }
                        eqIndexStore.add(payload.entityClassName, idx.colId, storedBytes, payload.rowId);
                    }
                }
            }
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_COL_PUT, new DbColPutResponse(payload.rowId, valueId));
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
