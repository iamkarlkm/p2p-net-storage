package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.List;

public class DbCompositeGroupSchema {
    public String group;
    public int length;
    public List<DbCompositeItemSchema> items;

    public DbCompositeGroupSchema() {
        this.items = new ArrayList<>();
    }

    public DbCompositeGroupSchema(String group, int length, List<DbCompositeItemSchema> items) {
        this.group = group;
        this.length = length;
        this.items = items == null ? new ArrayList<>() : items;
    }
}
