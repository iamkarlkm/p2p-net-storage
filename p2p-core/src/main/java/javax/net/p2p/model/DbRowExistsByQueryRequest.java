package javax.net.p2p.model;

public class DbRowExistsByQueryRequest {
    public String entityClassName;
    public DbQuery query;

    public DbRowExistsByQueryRequest() {
    }

    public DbRowExistsByQueryRequest(String entityClassName, DbQuery query) {
        this.entityClassName = entityClassName;
        this.query = query;
    }
}
