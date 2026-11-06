package javax.net.p2p.server.handler;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import com.q3lives.ds.database.columnar.index.EqIndexStore;
import com.q3lives.ds.database.schema.DynamicIndexUtil;
import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbColRemoveRequest;
import javax.net.p2p.model.DbColRemoveResponse;
import javax.net.p2p.model.P2PWrapper;

/**
 * 分布式节点列删除处理器。
 *
 * <p>按 rowId 中的 serverId 从 {@link DsNodeContext} 找到对应节点的
 * {@link DsPartitionManager}，再按低 8 位 partitionId 删除具体分区的列值，
 * 并同步维护等值索引。</p>
 */
public class DbNodeRemoveServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_NODE_REMOVE;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_NODE_REMOVE) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbColRemoveRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName");
            }
            if (payload.logicalName == null || payload.logicalName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing logicalName");
            }
            if (payload.rowId <= 0L) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid rowId");
            }

            DsPartitionManager manager = DsNodeContext.getManager(DsIdLayout.serverId(payload.rowId));
            if (manager == null) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "server not registered");
            }

            ColumnarStore store = manager.getStore(payload.rowId);
            File dbRoot = store.getDbRoot();
            DsHashSet ids = new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, payload.entityClassName));
            if (!ids.contains(payload.rowId)) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_NODE_REMOVE, new DbColRemoveResponse(false));
            }

            EqIndexStore eqIndexStore = new EqIndexStore(dbRoot);
            byte[] oldBytes = null;
            boolean hasEqIndex = eqIndexStore.exists(payload.entityClassName, payload.logicalName);
            if (hasEqIndex) {
                oldBytes = store.getValue(payload.entityClassName, payload.logicalName, payload.rowId);
            }

            boolean removed = store.removeValue(payload.entityClassName, payload.logicalName, payload.rowId);
            if (removed && hasEqIndex && oldBytes != null) {
                var idx = eqIndexStore.get(payload.entityClassName, payload.logicalName);
                if (idx != null && idx.colId > 0L) {
                    eqIndexStore.remove(payload.entityClassName, idx.colId, oldBytes, payload.rowId);
                }
            }
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_NODE_REMOVE, new DbColRemoveResponse(removed));
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
