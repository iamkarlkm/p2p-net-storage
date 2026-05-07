package javax.net.p2p.model;

public class DbRowQueryIdsRequest {
    public String entityClassName;
    public DbQuery query;
    public int offset;
    public int limit;

    public DbRowQueryIdsRequest() {
    }

    public DbRowQueryIdsRequest(String entityClassName, DbQuery query, int offset, int limit) {
        this.entityClassName = entityClassName;
        this.query = query;
        this.offset = offset;
        this.limit = limit;
    }
}

