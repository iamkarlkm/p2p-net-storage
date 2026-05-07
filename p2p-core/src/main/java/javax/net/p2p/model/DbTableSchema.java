package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DbTableSchema {
    public List<DbColumnSchema> columns;
    public Map<String, DbCompositeGroupSchema> compositeGroups;

    public DbTableSchema() {
        this.columns = new ArrayList<>();
        this.compositeGroups = new LinkedHashMap<>();
    }
}
