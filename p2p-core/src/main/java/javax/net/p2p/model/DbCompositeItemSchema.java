package javax.net.p2p.model;

public class DbCompositeItemSchema {
    public String name;
    public int startBits;
    public int endBits;

    public DbCompositeItemSchema() {
    }

    public DbCompositeItemSchema(String name, int startBits, int endBits) {
        this.name = name;
        this.startBits = startBits;
        this.endBits = endBits;
    }
}
