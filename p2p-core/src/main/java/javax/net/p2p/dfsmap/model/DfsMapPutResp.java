package javax.net.p2p.dfsmap.model;

public class DfsMapPutResp {
    private int status;
    private long epoch;
    private long key;
    private boolean hadOld;
    private long oldValue;

    public DfsMapPutResp() {
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
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

    public boolean isHadOld() {
        return hadOld;
    }

    public void setHadOld(boolean hadOld) {
        this.hadOld = hadOld;
    }

    public long getOldValue() {
        return oldValue;
    }

    public void setOldValue(long oldValue) {
        this.oldValue = oldValue;
    }
}
