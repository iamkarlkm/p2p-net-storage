package javax.net.p2p.server.handler;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.remote.DbEntityRelationsCodec;
import java.nio.ByteBuffer;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbEntityGetRequest;
import javax.net.p2p.model.DbEntityGetResponse;
import javax.net.p2p.model.P2PWrapper;

public class DbEntityGetServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_ENTITY_GET;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_ENTITY_GET) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbEntityGetRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.className == null || payload.className.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing className");
            }
            if (payload.id == 0L) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing id");
            }

            Class<?> raw = Class.forName(payload.className);
            if (!DsTableAdapter.class.isAssignableFrom(raw)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.DB_INVALID_ENTITY_CLASS, payload.className);
            }
            @SuppressWarnings("unchecked")
            Class<? extends DsTableAdapter> clazz = (Class<? extends DsTableAdapter>) raw;

            DsDatabaseLocal db = DsDatabaseLocal.load();
            DsTableAdapter entity = db.getTable(clazz, payload.id, payload.withRelations);
            if (entity == null) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.DB_ENTITY_NOT_FOUND, payload.className);
            }
            ByteBuffer buf = entity.toBytes();
            byte[] out = buf.array();
            byte[] relations = payload.withRelations ? DbEntityRelationsCodec.encode(entity) : null;
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_ENTITY_GET, new DbEntityGetResponse(payload.id, out, relations));
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
