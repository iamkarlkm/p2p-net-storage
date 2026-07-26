package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.List;

public class DbQueryCriterion {
    public DbQueryOp op;
    public String name;
    public String a;
    public String b;
    public List<String> list;

    public DbQueryCriterion() {
        this.list = new ArrayList<>();
    }

    public DbQueryCriterion(DbQueryOp op, String name, String a, String b, List<String> list) {
        this.op = op;
        this.name = name;
        this.a = a;
        this.b = b;
        this.list = list == null ? new ArrayList<>() : list;
    }
}

