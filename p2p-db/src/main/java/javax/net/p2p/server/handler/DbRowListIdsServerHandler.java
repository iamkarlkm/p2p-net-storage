package javax.net.p2p.server.handler;

import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.schema.DynamicIndexUtil;
import java.io.File;
import java.util.ArrayList;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbRowListIdsRequest;
import javax.net.p2p.model.DbRowListIdsResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;

public class DbRowListIdsServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_ROW_LIST_IDS;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_ROW_LIST_IDS) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbRowListIdsRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName");
            }
            int offset = Math.max(0, payload.offset);
            int limit = payload.limit <= 0 ? 1024 : payload.limit;

            DsDatabaseLocal db = DsDatabaseLocal.load();
            File dbRoot = db.getRoot();
            DsHashSet ids = new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, payload.entityClassName));

            ArrayList<Long> out = new ArrayList<>(Math.min(limit, 1024));
            int skipped = 0;
            for (Long v : ids) {
                if (v == null || v <= 0L) {
                    continue;
                }
                if (skipped < offset) {
                    skipped++;
                    continue;
                }
                out.add(v);
                if (out.size() >= limit) {
                    break;
                }
            }

            long[] arr = new long[out.size()];
            for (int i = 0; i < out.size(); i++) {
                arr[i] = out.get(i);
            }
            byte[] idsBytes = SerializationUtil.serialize(arr);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_ROW_LIST_IDS, new DbRowListIdsResponse(idsBytes));
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}

