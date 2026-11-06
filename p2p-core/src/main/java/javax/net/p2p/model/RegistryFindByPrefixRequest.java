package javax.net.p2p.model;

public class RegistryFindByPrefixRequest {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public long keyGlobalNodeId = 0L;
    public String dirPrefix;
    public int maxDepth = Integer.MAX_VALUE;
    public int limit = 1000;

    public RegistryFindByPrefixRequest() {}

    public RegistryFindByPrefixRequest(int sourceServerId, int sourcePartitionId,
                                        int targetServerId, int targetPartitionId,
                                        long keyGlobalNodeId, String dirPrefix,
                                        int maxDepth, int limit) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.keyGlobalNodeId = keyGlobalNodeId;
        this.dirPrefix = dirPrefix;
        this.maxDepth = maxDepth;
        this.limit = limit;
    }
}
