package javax.net.p2p.dfsmap.model;

public class DfsMapPingResp {
    private int status;
    private long epoch;
    private int serverId;
    private boolean tablesEnabled;
    private long totalSize;

    public DfsMapPingResp() {
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

    public int getServerId() {
        return serverId;
    }

    public void setServerId(int serverId) {
        this.serverId = serverId;
    }

    public boolean isTablesEnabled() {
        return tablesEnabled;
    }

    public void setTablesEnabled(boolean tablesEnabled) {
        this.tablesEnabled = tablesEnabled;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(long totalSize) {
        this.totalSize = totalSize;
    }
}
