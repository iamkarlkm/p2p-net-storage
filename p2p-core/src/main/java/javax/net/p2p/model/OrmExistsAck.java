package javax.net.p2p.model;

public class OrmExistsAck {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public boolean present;
    public String error;

    public OrmExistsAck() {}

    public OrmExistsAck(int sourceServerId, int sourcePartitionId,
                    int targetServerId, int targetPartitionId,
                    boolean present, String error) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.present = present;
        this.error = error;
    }
}
