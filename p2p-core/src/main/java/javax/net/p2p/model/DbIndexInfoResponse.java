package javax.net.p2p.model;

public class DbIndexInfoResponse {
    public boolean exists;
    public DbIndexDef index;

    public DbIndexInfoResponse() {
    }

    public DbIndexInfoResponse(boolean exists, DbIndexDef index) {
        this.exists = exists;
        this.index = index;
    }
}

