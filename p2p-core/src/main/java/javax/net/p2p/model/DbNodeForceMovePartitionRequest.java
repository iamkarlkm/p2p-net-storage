package javax.net.p2p.model;

/**
 * 分布式节点强制迁移分区请求。
 *
 * <p>当前里程碑为管理命令占位：接收方校验 serverId/partitionId 合法性后返回
 * ack，实际数据迁移涉及多节点协调，将在后续里程碑补齐。</p>
 */
public class DbNodeForceMovePartitionRequest {

    public int serverId;
    public int partitionId;
    public int targetServerId;

    public DbNodeForceMovePartitionRequest() {
    }

    public DbNodeForceMovePartitionRequest(int serverId, int partitionId) {
        this(serverId, partitionId, -1);
    }

    public DbNodeForceMovePartitionRequest(int serverId, int partitionId, int targetServerId) {
        this.serverId = serverId;
        this.partitionId = partitionId;
        this.targetServerId = targetServerId;
    }
}
