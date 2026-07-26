package javax.net.p2p.server.handler;

import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.schema.DynamicIndexUtil;
import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbColGetRequest;
import javax.net.p2p.model.DbColGetResponse;
import javax.net.p2p.model.P2PWrapper;

public class DbColGetServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_COL_GET;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_COL_GET) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbColGetRequest payload)) {
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
            if (!ids.contains(payload.rowId)) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_COL_GET, new DbColGetResponse(false, null));
            }
            byte[] bytes = new ColumnarStore(dbRoot).getValue(payload.entityClassName, payload.logicalName, payload.rowId);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_COL_GET, new DbColGetResponse(bytes != null, bytes));
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}

