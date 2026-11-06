package javax.net.p2p.model;

public class OrmCountAck {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public long count;
    public String error;

    public OrmCountAck() {}

    public OrmCountAck(int sourceServerId, int sourcePartitionId,
                     int targetServerId, int targetPartitionId,
                     long count, String error) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.count = count;
        this.error = error;
    }
}
