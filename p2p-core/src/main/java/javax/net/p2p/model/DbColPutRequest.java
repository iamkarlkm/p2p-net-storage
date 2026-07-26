package javax.net.p2p.model;

public class DbColPutRequest {
    public String entityClassName;
    public long rowId;
    public String logicalName;
    public byte[] valueBytes;
    public boolean upsertRow;

    public DbColPutRequest() {
    }

    public DbColPutRequest(String entityClassName, long rowId, String logicalName, byte[] valueBytes, boolean upsertRow) {
        this.entityClassName = entityClassName;
        this.rowId = rowId;
        this.logicalName = logicalName;
        this.valueBytes = valueBytes;
        this.upsertRow = upsertRow;
    }
}
