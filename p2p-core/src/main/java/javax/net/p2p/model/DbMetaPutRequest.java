package javax.net.p2p.model;

public class DbMetaPutRequest {
    public String entityClassName;
    public DbTableSchema schema;
    public boolean overwrite;

    public DbMetaPutRequest() {
    }

    public DbMetaPutRequest(String entityClassName, DbTableSchema schema, boolean overwrite) {
        this.entityClassName = entityClassName;
        this.schema = schema;
        this.overwrite = overwrite;
    }
}
