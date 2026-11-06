package javax.net.p2p.model;

public class RegistryGetRequest {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public long keyGlobalNodeId = 0L;
    public String keyPath;

    public RegistryGetRequest() {}

    public RegistryGetRequest(int sourceServerId, int sourcePartitionId,
                              int targetServerId, int targetPartitionId,
                              long keyGlobalNodeId, String keyPath) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.keyGlobalNodeId = keyGlobalNodeId;
        this.keyPath = keyPath;
    }
}
