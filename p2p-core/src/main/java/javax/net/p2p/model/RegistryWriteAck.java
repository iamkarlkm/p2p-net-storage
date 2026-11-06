package javax.net.p2p.model;

public class RegistryWriteAck {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public boolean ok;
    public long nodeIdOrRemoved;
    public String error;

    public RegistryWriteAck() {}

    public RegistryWriteAck(int sourceServerId, int sourcePartitionId,
                            int targetServerId, int targetPartitionId,
                            boolean ok, long nodeIdOrRemoved, String error) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.ok = ok;
        this.nodeIdOrRemoved = nodeIdOrRemoved;
        this.error = error;
    }
}
