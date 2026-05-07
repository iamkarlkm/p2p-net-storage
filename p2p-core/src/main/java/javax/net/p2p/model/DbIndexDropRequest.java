package javax.net.p2p.model;

public class DbIndexDropRequest {
    public String entityClassName;
    public String logicalName;

    public DbIndexDropRequest() {
    }

    public DbIndexDropRequest(String entityClassName, String logicalName) {
        this.entityClassName = entityClassName;
        this.logicalName = logicalName;
    }
}

