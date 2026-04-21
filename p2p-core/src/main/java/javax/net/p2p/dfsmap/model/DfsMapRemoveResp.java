package javax.net.p2p.dfsmap.model;

public class DfsMapRemoveResp {
    private int status;
    private long epoch;
    private long key;
    private boolean removed;
    private long oldValue;

    public DfsMapRemoveResp() {
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

    public boolean isRemoved() {
        return removed;
    }

    public void setRemoved(boolean removed) {
        this.removed = removed;
    }

    public long getOldValue() {
        return oldValue;
    }

    public void setOldValue(long oldValue) {
        this.oldValue = oldValue;
    }
}
