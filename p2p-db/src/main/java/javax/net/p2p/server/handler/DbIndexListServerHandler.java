package javax.net.p2p.server.handler;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.index.EqIndexMetaStore;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbIndexDef;
import javax.net.p2p.model.DbIndexListRequest;
import javax.net.p2p.model.DbIndexListResponse;
import javax.net.p2p.model.P2PWrapper;

public class DbIndexListServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_INDEX_LIST;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_INDEX_LIST) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbIndexListRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName");
            }

            File dbRoot = DsDatabaseLocal.load().getRoot();
            EqIndexMetaStore metaStore = new EqIndexMetaStore(dbRoot);
            List<EqIndexMetaStore.IndexDef> defs = metaStore.list(payload.entityClassName);
            ArrayList<DbIndexDef> out = new ArrayList<>(defs == null ? 0 : defs.size());
            if (defs != null) {
                for (EqIndexMetaStore.IndexDef d : defs) {
                    if (d == null || d.logicalName == null || d.logicalName.isBlank() || d.colId <= 0L) {
                        continue;
                    }
                    out.add(new DbIndexDef(d.logicalName, d.colId, "EQ"));
                }
            }
            out.sort(Comparator.comparing(a -> a.logicalName));
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_INDEX_LIST, new DbIndexListResponse(out));
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}

