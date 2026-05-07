package javax.net.p2p.model;

public class DbColGetResponse {
    public boolean found;
    public byte[] valueBytes;

    public DbColGetResponse() {
    }

    public DbColGetResponse(boolean found, byte[] valueBytes) {
        this.found = found;
        this.valueBytes = valueBytes;
    }
}
