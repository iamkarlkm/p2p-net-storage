package javax.net.p2p.model;

public class DbEntityExistsRequest {
    public String className;
    public long id;

    public DbEntityExistsRequest() {
    }

    public DbEntityExistsRequest(String className, long id) {
        this.className = className;
        this.id = id;
    }
}

