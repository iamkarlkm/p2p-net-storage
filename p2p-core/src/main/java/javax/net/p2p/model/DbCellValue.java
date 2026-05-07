package javax.net.p2p.model;

public class DbCellValue {
    public String name;
    public byte[] bytes;

    public DbCellValue() {
    }

    public DbCellValue(String name, byte[] bytes) {
        this.name = name;
        this.bytes = bytes;
    }
}
