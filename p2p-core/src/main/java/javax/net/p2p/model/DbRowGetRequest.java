package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.List;

public class DbRowGetRequest {
    public String entityClassName;
    public long rowId;
    public List<String> names;

    public DbRowGetRequest() {
        this.names = new ArrayList<>();
    }

    public DbRowGetRequest(String entityClassName, long rowId, List<String> names) {
        this.entityClassName = entityClassName;
        this.rowId = rowId;
        this.names = names == null ? new ArrayList<>() : names;
    }
}
