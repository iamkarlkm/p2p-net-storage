package javax.net.p2p.server.handler;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.TableMetaStore;
import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbMetaGetRequest;
import javax.net.p2p.model.DbMetaGetResponse;
import javax.net.p2p.model.DbMetaPutRequest;
import javax.net.p2p.model.P2PWrapper;

public class DbMetaPutServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_META_PUT;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_META_PUT) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbMetaPutRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName");
            }
            if (payload.schema == null) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing schema");
            }

            DsDatabaseLocal db = DsDatabaseLocal.load();
            File dbRoot = db.getRoot();

            TableMetaStore store = new TableMetaStore(dbRoot);
            store.ensureMeta(payload.entityClassName, payload.schema, payload.overwrite);

            DbMetaGetRequest get = new DbMetaGetRequest(payload.entityClassName, false, true, true);
            P2PWrapper metaResp = new DbMetaGetServerHandler().process(P2PWrapper.build(request.getSeq(), P2PCommand.DB_META_GET, get));
            if (metaResp.getCommand() != P2PCommand.R_OK_DB_META_GET || !(metaResp.getData() instanceof DbMetaGetResponse ok)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, "meta get failed after put");
            }
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_META_PUT, ok);
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}

