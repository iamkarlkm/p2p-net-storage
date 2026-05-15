package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.List;

public class DbQueryOrGroup {
    public List<DbQueryCriterion> where;

    public DbQueryOrGroup() {
        this.where = new ArrayList<>();
    }

    public DbQueryOrGroup(List<DbQueryCriterion> where) {
        this.where = where == null ? new ArrayList<>() : where;
    }
}

