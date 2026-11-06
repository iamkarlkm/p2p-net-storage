package javax.net.p2p.model;

public class OrmGetOneAck {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public boolean present;
    public byte[] entityBytes;
    public byte[] relations;
    public String error;

    public OrmGetOneAck() {}

    public OrmGetOneAck(int sourceServerId, int sourcePartitionId,
                      int targetServerId, int targetPartitionId,
                      boolean present, byte[] entityBytes, byte[] relations, String error) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.present = present;
        this.entityBytes = entityBytes;
        this.relations = relations;
        this.error = error;
    }
}
