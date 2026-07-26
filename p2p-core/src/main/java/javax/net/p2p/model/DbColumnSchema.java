package javax.net.p2p.model;

public class DbColumnSchema {
    public String name;
    public String javaType;
    public int length;
    public int precision;
    public int scale;

    public DbColumnSchema() {
    }

    public DbColumnSchema(String name, String javaType, int length, int precision, int scale) {
        this.name = name;
        this.javaType = javaType;
        this.length = length;
        this.precision = precision;
        this.scale = scale;
    }
}
