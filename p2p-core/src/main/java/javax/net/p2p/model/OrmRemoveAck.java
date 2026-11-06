package javax.net.p2p.model;

public class OrmRemoveAck {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public boolean ok;
    public String error;

    public OrmRemoveAck() {}

    public OrmRemoveAck(int sourceServerId, int sourcePartitionId,
                    int targetServerId, int targetPartitionId,
                    boolean ok, String error) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.ok = ok;
        this.error = error;
    }
}
