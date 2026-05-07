package javax.net.p2p.server.handler;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.index.EqIndexStore;
import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbIndexDropRequest;
import javax.net.p2p.model.DbIndexDropResponse;
import javax.net.p2p.model.P2PWrapper;

public class DbIndexDropServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_INDEX_DROP;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_INDEX_DROP) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbIndexDropRequest payload)) {
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

            File dbRoot = DsDatabaseLocal.load().getRoot();
            EqIndexStore idx = new EqIndexStore(dbRoot);
            boolean dropped = idx.drop(payload.entityClassName, payload.logicalName);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_INDEX_DROP, new DbIndexDropResponse(dropped));
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}

