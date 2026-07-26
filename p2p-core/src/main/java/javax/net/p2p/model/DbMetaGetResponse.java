package javax.net.p2p.model;

public class DbMetaGetResponse {
    public String entityClassName;
    public byte[] tableMetaYaml;
    public byte[] columnsMetaYaml;
    public String tableMetaSha256;
    public String columnsMetaSha256;

    public DbMetaGetResponse() {
    }

    public DbMetaGetResponse(
        String entityClassName,
        byte[] tableMetaYaml,
        byte[] columnsMetaYaml,
        String tableMetaSha256,
        String columnsMetaSha256
    ) {
        this.entityClassName = entityClassName;
        this.tableMetaYaml = tableMetaYaml;
        this.columnsMetaYaml = columnsMetaYaml;
        this.tableMetaSha256 = tableMetaSha256;
        this.columnsMetaSha256 = columnsMetaSha256;
    }
}
