package javax.net.p2p.server.handler;

import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.schema.DynamicIndexUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbCellValue;
import javax.net.p2p.model.DbRowGetRequest;
import javax.net.p2p.model.DbRowGetResponse;
import javax.net.p2p.model.P2PWrapper;

public class DbRowGetServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_ROW_GET;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_ROW_GET) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbRowGetRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName");
            }
            if (payload.rowId <= 0L) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid rowId");
            }

            DsDatabaseLocal db = DsDatabaseLocal.load();
            File dbRoot = db.getRoot();
            DsHashSet ids = new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, payload.entityClassName));
            if (!ids.contains(payload.rowId)) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_ROW_GET, new DbRowGetResponse(payload.rowId, new ArrayList<>()));
            }

            ColumnarStore store = new ColumnarStore(dbRoot);
            List<String> names = payload.names;
            if (names == null || names.isEmpty()) {
                names = allNames(new TableMetaStore(dbRoot).getMeta(payload.entityClassName));
            }

            ArrayList<DbCellValue> out = new ArrayList<>(names.size());
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                byte[] bytes = store.getValue(payload.entityClassName, name, payload.rowId);
                out.add(new DbCellValue(name, bytes));
            }
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_ROW_GET, new DbRowGetResponse(payload.rowId, out));
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }

    private static List<String> allNames(TableMetaStore.TableMeta meta) {
        ArrayList<String> out = new ArrayList<>();
        if (meta == null) {
            return out;
        }
        if (meta.columns != null) {
            for (TableMetaStore.ColumnDef c : meta.columns) {
                if (c == null || c.name == null || c.name.isBlank()) {
                    continue;
                }
                out.add(c.name);
            }
        }
        if (meta.compositeGroups != null) {
            for (TableMetaStore.CompositeGroup g : meta.compositeGroups.values()) {
                if (g == null || g.group == null || g.group.isBlank()) {
                    continue;
                }
                out.add("@composite:" + g.group);
            }
        }
        return out;
    }
}

