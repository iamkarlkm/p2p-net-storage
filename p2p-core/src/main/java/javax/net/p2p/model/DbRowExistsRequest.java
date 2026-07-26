package javax.net.p2p.model;

public class DbRowExistsRequest {
    public String entityClassName;
    public long rowId;

    public DbRowExistsRequest() {
    }

    public DbRowExistsRequest(String entityClassName, long rowId) {
        this.entityClassName = entityClassName;
        this.rowId = rowId;
    }
}
