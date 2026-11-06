package javax.net.p2p.model;

public class RegistryMkdirsRequest {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public long keyGlobalNodeId = 0L;
    public String dirPath;

    public RegistryMkdirsRequest() {}

    public RegistryMkdirsRequest(int sourceServerId, int sourcePartitionId,
                                 int targetServerId, int targetPartitionId,
                                 long keyGlobalNodeId, String dirPath) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.keyGlobalNodeId = keyGlobalNodeId;
        this.dirPath = dirPath;
    }
}
