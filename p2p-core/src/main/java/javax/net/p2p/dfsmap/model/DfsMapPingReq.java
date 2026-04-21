package javax.net.p2p.dfsmap.model;

public class DfsMapPingReq {
    private int apiVersion;
    private long epoch;

    public DfsMapPingReq() {
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
}
