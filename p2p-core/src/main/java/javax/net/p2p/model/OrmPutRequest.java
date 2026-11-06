package javax.net.p2p.model;

public class OrmPutRequest {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public long globalRowId = 0L;
    public String className;
    public byte[] entityBytes;
    public boolean withRelations;
    public byte[] relations;

    public OrmPutRequest() {}

    public OrmPutRequest(int sourceServerId, int sourcePartitionId,
                         int targetServerId, int targetPartitionId,
                         long globalRowId, String className,
                         byte[] entityBytes, boolean withRelations, byte[] relations) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.globalRowId = globalRowId;
        this.className = className;
        this.entityBytes = entityBytes;
        this.withRelations = withRelations;
        this.relations = relations;
    }
}
