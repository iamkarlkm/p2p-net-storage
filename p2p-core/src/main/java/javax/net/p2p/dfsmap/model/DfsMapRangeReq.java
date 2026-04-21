package javax.net.p2p.dfsmap.model;

public class DfsMapRangeReq {
    private int apiVersion;
    private long epoch;
    private long start;
    private int count;
    private boolean keysOnly;

    public DfsMapRangeReq() {
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

    public long getStart() {
        return start;
    }

    public void setStart(long start) {
        this.start = start;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public boolean isKeysOnly() {
        return keysOnly;
    }

    public void setKeysOnly(boolean keysOnly) {
        this.keysOnly = keysOnly;
    }
}
