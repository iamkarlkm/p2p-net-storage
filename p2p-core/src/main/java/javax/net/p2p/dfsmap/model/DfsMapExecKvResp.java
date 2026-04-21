package javax.net.p2p.dfsmap.model;

public class DfsMapExecKvResp {
    private int status;
    private long epoch;
    private boolean affected;
    private long valueOrOldValue;
    private int redirectServerId;

    public DfsMapExecKvResp() {
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

    public boolean isAffected() {
        return affected;
    }

    public void setAffected(boolean affected) {
        this.affected = affected;
    }

    public long getValueOrOldValue() {
        return valueOrOldValue;
    }

    public void setValueOrOldValue(long valueOrOldValue) {
        this.valueOrOldValue = valueOrOldValue;
    }

    public int getRedirectServerId() {
        return redirectServerId;
    }

    public void setRedirectServerId(int redirectServerId) {
        this.redirectServerId = redirectServerId;
    }
}
