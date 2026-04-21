package javax.net.p2p.dfsmap.model;

public class DfsMapRangeLocalResp {
    private int status;
    private long epoch;
    private long nextCursor;
    private int emitted;
    private long[] keys;
    private long[] values;
    private int redirectServerId;

    public DfsMapRangeLocalResp() {
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

    public long getNextCursor() {
        return nextCursor;
    }

    public void setNextCursor(long nextCursor) {
        this.nextCursor = nextCursor;
    }

    public int getEmitted() {
        return emitted;
    }

    public void setEmitted(int emitted) {
        this.emitted = emitted;
    }

    public long[] getKeys() {
        return keys;
    }

    public void setKeys(long[] keys) {
        this.keys = keys;
    }

    public long[] getValues() {
        return values;
    }

    public void setValues(long[] values) {
        this.values = values;
    }

    public int getRedirectServerId() {
        return redirectServerId;
    }

    public void setRedirectServerId(int redirectServerId) {
        this.redirectServerId = redirectServerId;
    }
}
