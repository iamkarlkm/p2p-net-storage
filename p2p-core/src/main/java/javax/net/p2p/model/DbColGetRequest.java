package javax.net.p2p.model;

public class DbColGetRequest {
    public String entityClassName;
    public long rowId;
    public String logicalName;

    public DbColGetRequest() {
    }

    public DbColGetRequest(String entityClassName, long rowId, String logicalName) {
        this.entityClassName = entityClassName;
        this.rowId = rowId;
        this.logicalName = logicalName;
    }
}
