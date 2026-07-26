package javax.net.p2p.model;

public class DbIndexInfoRequest {
    public String entityClassName;
    public String logicalName;

    public DbIndexInfoRequest() {
    }

    public DbIndexInfoRequest(String entityClassName, String logicalName) {
        this.entityClassName = entityClassName;
        this.logicalName = logicalName;
    }
}

