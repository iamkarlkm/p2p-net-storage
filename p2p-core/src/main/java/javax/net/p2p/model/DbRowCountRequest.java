package javax.net.p2p.model;

public class DbRowCountRequest {
    public String entityClassName;
    public DbQuery query;

    public DbRowCountRequest() {
    }

    public DbRowCountRequest(String entityClassName, DbQuery query) {
        this.entityClassName = entityClassName;
        this.query = query;
    }
}
