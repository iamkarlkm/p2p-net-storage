package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.List;

public class DbQuery {
    public List<DbQueryCriterion> where;
    public List<DbQueryOrder> orderBy;

    public DbQuery() {
        this.where = new ArrayList<>();
        this.orderBy = new ArrayList<>();
    }
}

