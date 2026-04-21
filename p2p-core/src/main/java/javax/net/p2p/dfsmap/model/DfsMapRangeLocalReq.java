package javax.net.p2p.dfsmap.model;

public class DfsMapRangeLocalReq {
    private int apiVersion;
    private long epoch;
    private int ownerServerId;
    private int tableId;
    private long cursor;
    private int limit;
    private boolean keysOnly;

    public DfsMapRangeLocalReq() {
    }

    public int getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(int apiVersion) {
        this.apiVersion = apiVersion;
    }

    public long getEpoch() {
        return epoch;
    }

    public void setEpoch(long epoch) {
        this.epoch = epoch;
    }

    public int getOwnerServerId() {
        return ownerServerId;
    }

    public void setOwnerServerId(int ownerServerId) {
        this.ownerServerId = ownerServerId;
    }

    public int getTableId() {
        return tableId;
    }

    public void setTableId(int tableId) {
        this.tableId = tableId;
    }

    public long getCursor() {
        return cursor;
    }

    public void setCursor(long cursor) {
        this.cursor = cursor;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public boolean isKeysOnly() {
        return keysOnly;
    }

    public void setKeysOnly(boolean keysOnly) {
        this.keysOnly = keysOnly;
    }
}
