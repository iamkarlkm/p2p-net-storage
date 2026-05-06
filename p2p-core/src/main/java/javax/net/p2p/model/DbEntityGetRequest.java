package javax.net.p2p.model;

public class DbEntityGetRequest {
    public String className;
    public long id;
    public boolean withRelations;

    public DbEntityGetRequest() {
    }

    public DbEntityGetRequest(String className, long id, boolean withRelations) {
        this.className = className;
        this.id = id;
        this.withRelations = withRelations;
    }
}

