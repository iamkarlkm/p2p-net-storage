package javax.net.p2p.server.handler;

import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.RowIdSequenceStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.schema.DynamicIndexUtil;
import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbRowAllocRequest;
import javax.net.p2p.model.DbRowAllocResponse;
import javax.net.p2p.model.P2PWrapper;

public class DbRowAllocServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_ROW_ALLOC;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_ROW_ALLOC) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbRowAllocRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName");
            }

            DsDatabaseLocal db = DsDatabaseLocal.load();
            File dbRoot = db.getRoot();

            TableMetaStore.TableMeta meta = new TableMetaStore(dbRoot).getMeta(payload.entityClassName);
            if (meta.signature == null || meta.signature.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing table meta");
            }

            long rowId = new RowIdSequenceStore(dbRoot).allocate(payload.entityClassName);
            new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, payload.entityClassName)).add(rowId);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_ROW_ALLOC, new DbRowAllocResponse(rowId));
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}

