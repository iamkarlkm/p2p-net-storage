package javax.net.p2p.server.handler;

import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.schema.EntityIndexUtil;
import java.io.IOException;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbEntityExistsRequest;
import javax.net.p2p.model.DbEntityExistsResponse;
import javax.net.p2p.model.P2PWrapper;

public class DbEntityExistsServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_ENTITY_EXISTS;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_ENTITY_EXISTS) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbEntityExistsRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.className == null || payload.className.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing className");
            }
            if (payload.id <= 0L) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid id");
            }

            Class<?> raw = Class.forName(payload.className);
            if (!DsTableAdapter.class.isAssignableFrom(raw)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.DB_INVALID_ENTITY_CLASS, payload.className);
            }

            DsDatabaseLocal db = DsDatabaseLocal.load();
            boolean exists = containsInIdsIndex(db, raw, payload.id);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_ENTITY_EXISTS, new DbEntityExistsResponse(exists));
        } catch (IOException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.FILE_IO_ERROR, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }

    private boolean containsInIdsIndex(DsDatabaseLocal db, Class<?> clazz, long id) throws IOException {
        @SuppressWarnings("unchecked")
        Class<? extends DsTableAdapter> c = (Class<? extends DsTableAdapter>) clazz;
        EntityIndexUtil.IndexDef index = EntityIndexUtil.indexOf(db.getRoot(), c);
        DsHashSet idSet = new DsHashSet(index.idsFile);
        return idSet.contains(id);
    }
}
