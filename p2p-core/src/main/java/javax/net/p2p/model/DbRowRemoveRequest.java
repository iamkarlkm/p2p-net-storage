package javax.net.p2p.model;

public class DbRowRemoveRequest {
    public String entityClassName;
    public long rowId;

    public DbRowRemoveRequest() {
    }

    public DbRowRemoveRequest(String entityClassName, long rowId) {
        this.entityClassName = entityClassName;
        this.rowId = rowId;
    }
}
