package javax.net.p2p.model;

public class DbRowAllocRequest {
    public String entityClassName;

    public DbRowAllocRequest() {
    }

    public DbRowAllocRequest(String entityClassName) {
        this.entityClassName = entityClassName;
    }
}
