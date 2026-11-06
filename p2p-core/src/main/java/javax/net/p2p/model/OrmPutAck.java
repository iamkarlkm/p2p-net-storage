package javax.net.p2p.model;

public class OrmPutAck {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public long rowId;
    public String error;

    public OrmPutAck() {}

    public OrmPutAck(int sourceServerId, int sourcePartitionId,
                      int targetServerId, int targetPartitionId,
                      long rowId, String error) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.rowId = rowId;
        this.error = error;
    }
}
