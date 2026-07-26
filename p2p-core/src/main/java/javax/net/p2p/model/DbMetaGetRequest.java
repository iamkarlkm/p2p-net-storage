package javax.net.p2p.model;

public class DbMetaGetRequest {
    public String entityClassName;
    public boolean ensureFresh;
    public boolean includeTableMeta;
    public boolean includeColumnsMeta;

    public DbMetaGetRequest() {
    }

    public DbMetaGetRequest(String entityClassName, boolean ensureFresh, boolean includeTableMeta, boolean includeColumnsMeta) {
        this.entityClassName = entityClassName;
        this.ensureFresh = ensureFresh;
        this.includeTableMeta = includeTableMeta;
        this.includeColumnsMeta = includeColumnsMeta;
    }
}
