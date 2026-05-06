package javax.net.p2p.model;

public class DbEntityRemoveRequest {
    public String className;
    public long id;
    public boolean withRelations;

    public DbEntityRemoveRequest() {
    }

    public DbEntityRemoveRequest(String className, long id, boolean withRelations) {
        this.className = className;
        this.id = id;
        this.withRelations = withRelations;
    }
}

