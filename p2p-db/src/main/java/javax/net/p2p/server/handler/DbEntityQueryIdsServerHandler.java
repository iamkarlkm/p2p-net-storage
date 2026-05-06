package javax.net.p2p.server.handler;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbEntityQueryIdsRequest;
import javax.net.p2p.model.DbEntityQueryIdsResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;

public class DbEntityQueryIdsServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_ENTITY_QUERY_IDS;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_ENTITY_QUERY_IDS) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbEntityQueryIdsRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.className == null || payload.className.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing className");
            }
            if (payload.queryWrapperBytes == null || payload.queryWrapperBytes.length == 0) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing queryWrapperBytes");
            }

            Class<?> raw = Class.forName(payload.className);
            if (!DsTableAdapter.class.isAssignableFrom(raw)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.DB_INVALID_ENTITY_CLASS, payload.className);
            }

            @SuppressWarnings("unchecked")
            Class<? extends DsTableAdapter> clazz = (Class<? extends DsTableAdapter>) raw;
            QueryWrapper wrapper = SerializationUtil.deserialize(QueryWrapper.class, payload.queryWrapperBytes);
            GenericManager manager = new GenericManager(clazz);
            List<? extends DsTableAdapter> entities = manager.findRangeByWrapper(wrapper, payload.start, payload.end);
            long[] ids = new long[entities.size()];
            for (int i = 0; i < entities.size(); i++) {
                DsTableAdapter e = entities.get(i);
                ids[i] = e == null || e.getId() == null ? 0L : e.getId();
            }
            byte[] idsBytes = SerializationUtil.serialize(ids);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_ENTITY_QUERY_IDS, new DbEntityQueryIdsResponse(idsBytes));
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
