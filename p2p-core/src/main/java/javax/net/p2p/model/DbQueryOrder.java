package javax.net.p2p.model;

public class DbQueryOrder {
    public String name;
    public boolean asc;

    public DbQueryOrder() {
    }

    public DbQueryOrder(String name, boolean asc) {
        this.name = name;
        this.asc = asc;
    }
}

