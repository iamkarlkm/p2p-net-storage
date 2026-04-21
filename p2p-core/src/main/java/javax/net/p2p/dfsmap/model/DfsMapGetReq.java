package javax.net.p2p.dfsmap.model;

public class DfsMapGetReq {
    private int apiVersion;
    private long epoch;
    private long key;

    public DfsMapGetReq() {
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
}
