package javax.net.p2p.model;

public class DbEntityGetResponse {
    public long id;
    public byte[] bytes;
    public byte[] relations;

    public DbEntityGetResponse() {
    }

    public DbEntityGetResponse(long id, byte[] bytes) {
        this.id = id;
        this.bytes = bytes;
    }
    
    public DbEntityGetResponse(long id, byte[] bytes, byte[] relations) {
        this.id = id;
        this.bytes = bytes;
        this.relations = relations;
    }
}
