package javax.net.p2p.model;

public class DbIndexDef {
    public String logicalName;
    public long colId;
    public String type;

    public DbIndexDef() {
    }

    public DbIndexDef(String logicalName, long colId, String type) {
        this.logicalName = logicalName;
        this.colId = colId;
        this.type = type;
    }
}

