package javax.net.p2p.dfsmap.model;

public class DfsMapRangeResp {
    private int status;
    private long epoch;
    private long start;
    private int requestedCount;
    private int emitted;
    private long[] keys;
    private long[] values;

    public DfsMapRangeResp() {
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

    public long getStart() {
        return start;
    }

    public void setStart(long start) {
        this.start = start;
    }

    public int getRequestedCount() {
        return requestedCount;
    }

    public void setRequestedCount(int requestedCount) {
        this.requestedCount = requestedCount;
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
}
