package javax.net.p2p.dfsmap.model;

public class DfsMapTablesEnableResp {
    private int status;
    private long epoch;
    private int serverId;
    private long migrationId;
    private boolean tablesEnabled;
    private String message;
    private long nextCursor;
    private int emitted;
    private long[] keys;
    private long[] values;

    public DfsMapTablesEnableResp() {
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

    public long getMigrationId() {
        return migrationId;
    }

    public void setMigrationId(long migrationId) {
        this.migrationId = migrationId;
    }

    public boolean isTablesEnabled() {
        return tablesEnabled;
    }

    public void setTablesEnabled(boolean tablesEnabled) {
        this.tablesEnabled = tablesEnabled;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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
}
