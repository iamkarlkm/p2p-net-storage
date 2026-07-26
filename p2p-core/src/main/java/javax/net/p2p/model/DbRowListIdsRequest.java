package javax.net.p2p.model;

public class DbRowListIdsRequest {
    public String entityClassName;
    public int offset;
    public int limit;

    public DbRowListIdsRequest() {
    }

    public DbRowListIdsRequest(String entityClassName, int offset, int limit) {
        this.entityClassName = entityClassName;
        this.offset = offset;
        this.limit = limit;
    }
}
