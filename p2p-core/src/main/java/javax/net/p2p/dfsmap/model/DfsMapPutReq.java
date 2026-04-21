package javax.net.p2p.dfsmap.model;

public class DfsMapPutReq {
    private int apiVersion;
    private long epoch;
    private long key;
    private long value;
    private boolean returnOldValue;

    public DfsMapPutReq() {
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
}
