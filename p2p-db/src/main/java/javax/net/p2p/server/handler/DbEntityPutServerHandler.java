package javax.net.p2p.server.handler;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.remote.DbEntityRelationsCodec;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.schema.EntityIndexUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbEntityPutRequest;
import javax.net.p2p.model.DbEntityPutResponse;
import javax.net.p2p.model.P2PWrapper;

public class DbEntityPutServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_ENTITY_PUT;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_ENTITY_PUT) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbEntityPutRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.className == null || payload.className.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing className");
            }
            if (payload.bytes == null || payload.bytes.length == 0) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing bytes");
            }

            Class<?> raw = Class.forName(payload.className);
            if (!DsTableAdapter.class.isAssignableFrom(raw)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.DB_INVALID_ENTITY_CLASS, payload.className);
            }

            @SuppressWarnings("unchecked")
            Class<? extends DsTableAdapter> clazz = (Class<? extends DsTableAdapter>) raw;
            DsTableAdapter entity = clazz.getDeclaredConstructor().newInstance();
            entity.load(ByteBuffer.wrap(payload.bytes));
            if (payload.withRelations && payload.relations != null && payload.relations.length > 0) {
                DbEntityRelationsCodec.apply(entity, payload.relations);
            }

            DsDatabaseLocal db = DsDatabaseLocal.load();
            long id = db.putTable(entity, payload.withRelations);
            addToIdsIndex(db, clazz, id);

            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_ENTITY_PUT, new DbEntityPutResponse(id));
        } catch (IOException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.FILE_IO_ERROR, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }

    private void addToIdsIndex(DsDatabaseLocal db, Class<? extends DsTableAdapter> clazz, long id) throws IOException {
        EntityIndexUtil.IndexDef index = EntityIndexUtil.indexOf(db.getRoot(), clazz);
        DsHashSet idSet = new DsHashSet(index.idsFile);
        idSet.add(id);
    }
}
