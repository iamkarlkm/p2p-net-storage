package javax.net.p2p.server.handler;

import com.q3lives.ds.binlog.DsBinlogContext;
import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import com.q3lives.ds.database.schema.DynamicIndexUtil;
import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbNodeReplicateAck;
import javax.net.p2p.model.DbNodeReplicateFrame;
import javax.net.p2p.model.P2PWrapper;

/**
 * 分布式节点复制帧处理器。
 *
 * <p>副本节点接收主节点发送的 {@link DbNodeReplicateFrame}，在
 * {@link DsBinlogContext#setInReplay(boolean)} 线程上下文中直接应用到
 * {@link ColumnarStore}，不写本地 binlog，实现最终一致的副本同步。</p>
 */
public class DbNodeReplicateFrameServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_NODE_REPLICATE_FRAME;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_NODE_REPLICATE_FRAME) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbNodeReplicateFrame payload)) {
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
                // 副本节点可能保存来自多个主 serverId 的数据；单节点场景下回退到本地唯一 manager
                int[] locals = DsNodeContext.listServerIds();
                if (locals.length == 1) {
                    manager = DsNodeContext.getManager(locals[0]);
                }
            }
            if (manager == null) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "server not registered");
            }

            ColumnarStore store = manager.getStore(payload.rowId);
            File dbRoot = store.getDbRoot();

            boolean wasInReplay = DsBinlogContext.isInReplay();
            try {
                DsBinlogContext.setInReplay(true);
                if (payload.remove) {
                    store.removeValue(payload.entityClassName, payload.logicalName, payload.rowId);
                    return P2PWrapper.build(request.getSeq(), P2PCommand.DB_NODE_REPLICATE_ACK,
                            new DbNodeReplicateAck(true, null));
                }

                if (payload.upsertRow) {
                    DsHashSet ids = new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, payload.entityClassName));
                    ids.add(payload.rowId);
                }
                store.putValue(payload.entityClassName, payload.logicalName, payload.rowId, payload.valueBytes);
                return P2PWrapper.build(request.getSeq(), P2PCommand.DB_NODE_REPLICATE_ACK,
                        new DbNodeReplicateAck(true, null));
            } finally {
                DsBinlogContext.setInReplay(wasInReplay);
            }
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
