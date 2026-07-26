package javax.net.p2p.server.handler;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.index.EqIndexMetaStore;
import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbIndexDef;
import javax.net.p2p.model.DbIndexInfoRequest;
import javax.net.p2p.model.DbIndexInfoResponse;
import javax.net.p2p.model.P2PWrapper;

public class DbIndexInfoServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_INDEX_INFO;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_INDEX_INFO) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbIndexInfoRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName");
            }
            if (payload.logicalName == null || payload.logicalName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing logicalName");
            }

            File dbRoot = DsDatabaseLocal.load().getRoot();
            EqIndexMetaStore metaStore = new EqIndexMetaStore(dbRoot);
            EqIndexMetaStore.IndexDef def = metaStore.get(payload.entityClassName, payload.logicalName);
            if (def == null || def.colId <= 0L) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_INDEX_INFO, new DbIndexInfoResponse(false, null));
            }
            DbIndexDef out = new DbIndexDef(def.logicalName, def.colId, "EQ");
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_INDEX_INFO, new DbIndexInfoResponse(true, out));
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}

