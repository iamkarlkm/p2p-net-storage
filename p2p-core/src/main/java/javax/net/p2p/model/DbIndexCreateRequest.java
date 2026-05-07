package javax.net.p2p.model;

public class DbIndexCreateRequest {
    public String entityClassName;
    public String logicalName;

    public DbIndexCreateRequest() {
    }

    public DbIndexCreateRequest(String entityClassName, String logicalName) {
        this.entityClassName = entityClassName;
        this.logicalName = logicalName;
    }
}

