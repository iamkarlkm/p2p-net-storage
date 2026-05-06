package javax.net.p2p.model;

public class DbEntityPutRequest {
    public String className;
    public byte[] bytes;
    public boolean withRelations;
    public byte[] relations;

    public DbEntityPutRequest() {
    }

    public DbEntityPutRequest(String className, byte[] bytes, boolean withRelations) {
        this.className = className;
        this.bytes = bytes;
        this.withRelations = withRelations;
    }
    
    public DbEntityPutRequest(String className, byte[] bytes, boolean withRelations, byte[] relations) {
        this.className = className;
        this.bytes = bytes;
        this.withRelations = withRelations;
        this.relations = relations;
    }
}
