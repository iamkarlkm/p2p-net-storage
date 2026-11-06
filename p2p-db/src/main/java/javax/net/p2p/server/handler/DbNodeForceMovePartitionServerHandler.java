package javax.net.p2p.server.handler;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbNodeForceMovePartitionRequest;
import javax.net.p2p.model.DbNodeForceMovePartitionResponse;
import javax.net.p2p.model.P2PWrapper;

/**
 * 分布式节点强制迁移分区管理命令处理器。
 *
 * <p>当前里程碑仅完成参数校验与本地分区存在性检查，返回 acknowledged。
 * 实际数据迁移涉及多节点协调与目录拷贝，将在后续里程碑补齐。</p>
 */
public class DbNodeForceMovePartitionServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_NODE_FORCE_MOVE_PARTITION;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_NODE_FORCE_MOVE_PARTITION) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbNodeForceMovePartitionRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.serverId < 0 || payload.serverId > DsIdLayout.SERVER_ID_MASK) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "serverId out of range");
            }
            if (payload.partitionId < 0 || payload.partitionId > DsIdLayout.PARTITION_MASK) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "partitionId out of range");
            }

            DsPartitionManager manager = DsNodeContext.getManager(payload.serverId);
            if (manager == null) {
                int[] locals = DsNodeContext.listServerIds();
                if (locals.length == 1) {
                    manager = DsNodeContext.getManager(locals[0]);
                }
            }
            if (manager == null) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "server not registered");
            }
            if (payload.partitionId >= manager.getPartitionCount()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "partitionId out of range");
            }

            // 校验分区目录存在；实际迁移待后续实现
            manager.getPartitionRoot(payload.partitionId);

            DbNodeForceMovePartitionResponse response = new DbNodeForceMovePartitionResponse(true,
                    "acknowledged: forceMovePartition for serverId=" + payload.serverId
                            + ", partitionId=" + payload.partitionId + " accepted (data movement pending)");
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_NODE_FORCE_MOVE_PARTITION, response);
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
