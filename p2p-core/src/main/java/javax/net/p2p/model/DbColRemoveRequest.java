package javax.net.p2p.model;

public class DbColRemoveRequest {
    public String entityClassName;
    public long rowId;
    public String logicalName;

    public DbColRemoveRequest() {
    }

    public DbColRemoveRequest(String entityClassName, long rowId, String logicalName) {
        this.entityClassName = entityClassName;
        this.rowId = rowId;
        this.logicalName = logicalName;
    }
}
