package javax.net.p2p.model;

public class DbRowQueryIdsStreamRequest {
    public String entityClassName;
    public DbQuery query;
    public int offset;
    public int limit;
    public int chunkSize;

    public DbRowQueryIdsStreamRequest() {
    }

    public DbRowQueryIdsStreamRequest(String entityClassName, DbQuery query, int offset, int limit, int chunkSize) {
        this.entityClassName = entityClassName;
        this.query = query;
        this.offset = offset;
        this.limit = limit;
        this.chunkSize = chunkSize;
    }
}
