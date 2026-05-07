package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.List;

public class DbIndexListResponse {
    public List<DbIndexDef> indexes = new ArrayList<>();

    public DbIndexListResponse() {
    }

    public DbIndexListResponse(List<DbIndexDef> indexes) {
        if (indexes != null) {
            this.indexes.addAll(indexes);
        }
    }
}

