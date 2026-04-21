package javax.net.p2p.dfsmap.model;

public class DfsMapExecKvReq {
    private int apiVersion;
    private long epoch;
    private DfsMapOp op;
    private long key;
    private long value;
    private boolean returnOldValue;
    private int ownerServerId;
    private int tableId;

    public DfsMapExecKvReq() {
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

    public DfsMapOp getOp() {
        return op;
    }

    public void setOp(DfsMapOp op) {
        this.op = op;
    }

    public long getKey() {
        return key;
    }

    public void setKey(long key) {
        this.key = key;
    }

    public long getValue() {
        return value;
    }

    public void setValue(long value) {
        this.value = value;
    }

    public boolean isReturnOldValue() {
        return returnOldValue;
    }

    public void setReturnOldValue(boolean returnOldValue) {
        this.returnOldValue = returnOldValue;
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
}
