package javax.net.p2p.model;

public class DbColPutResponse {
    public long rowId;
    public long valueId;

    public DbColPutResponse() {
    }

    public DbColPutResponse(long rowId, long valueId) {
        this.rowId = rowId;
        this.valueId = valueId;
    }
}
