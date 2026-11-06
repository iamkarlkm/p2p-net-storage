package javax.net.p2p.model;

/**
 * 分布式节点强制迁移分区响应。
 */
public class DbNodeForceMovePartitionResponse {

    public boolean ok;
    public String message;

    public DbNodeForceMovePartitionResponse() {
    }

    public DbNodeForceMovePartitionResponse(boolean ok, String message) {
        this.ok = ok;
        this.message = message;
    }
}
