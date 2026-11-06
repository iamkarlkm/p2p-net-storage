package javax.net.p2p.model;

public class ExtaRemoveAllCommentsRequest {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public long keyGlobalNodeId = 0L;
    public String entryKey;

    public ExtaRemoveAllCommentsRequest() {}

    public ExtaRemoveAllCommentsRequest(int sourceServerId, int sourcePartitionId,
                                        int targetServerId, int targetPartitionId,
                                        long keyGlobalNodeId, String entryKey) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.keyGlobalNodeId = keyGlobalNodeId;
        this.entryKey = entryKey;
    }
}
