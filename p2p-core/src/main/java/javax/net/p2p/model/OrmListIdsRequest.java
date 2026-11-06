package javax.net.p2p.model;

public class OrmListIdsRequest {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public long globalRowId = 0L;
    public String className;
    public byte[] queryWrapperBytes;
    public int start;
    public int end;

    public OrmListIdsRequest() {}

    public OrmListIdsRequest(int sourceServerId, int sourcePartitionId,
                             int targetServerId, int targetPartitionId,
                             long globalRowId, String className,
                             byte[] queryWrapperBytes, int start, int end) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.globalRowId = globalRowId;
        this.className = className;
        this.queryWrapperBytes = queryWrapperBytes;
        this.start = start;
        this.end = end;
    }
}
