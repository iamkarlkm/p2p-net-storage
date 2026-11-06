package javax.net.p2p.model;

public class OrmGetRequest {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public long globalRowId = 0L;
    public String className;
    public long rowId;
    public boolean withRelations;

    public OrmGetRequest() {}

    public OrmGetRequest(int sourceServerId, int sourcePartitionId,
                         int targetServerId, int targetPartitionId,
                         long globalRowId, String className,
                         long rowId, boolean withRelations) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.globalRowId = globalRowId;
        this.className = className;
        this.rowId = rowId;
        this.withRelations = withRelations;
    }
}
