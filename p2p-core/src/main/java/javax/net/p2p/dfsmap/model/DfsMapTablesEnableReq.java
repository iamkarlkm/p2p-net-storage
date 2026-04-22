package javax.net.p2p.dfsmap.model;

public class DfsMapTablesEnableReq {
    private int apiVersion;
    private long epoch;
    private long migrationId;
    private int coordinatorServerId;
    private boolean forwarded;
    private boolean force;
    private boolean stepByStep;
    private long cursor;
    private int limit;
    private long[] keys;
    private long[] values;

    public DfsMapTablesEnableReq() {
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

    public long getMigrationId() {
        return migrationId;
    }

    public void setMigrationId(long migrationId) {
        this.migrationId = migrationId;
    }

    public int getCoordinatorServerId() {
        return coordinatorServerId;
    }

    public void setCoordinatorServerId(int coordinatorServerId) {
        this.coordinatorServerId = coordinatorServerId;
    }

    public boolean isForwarded() {
        return forwarded;
    }

    public void setForwarded(boolean forwarded) {
        this.forwarded = forwarded;
    }

    public boolean isForce() {
        return force;
    }

    public void setForce(boolean force) {
        this.force = force;
    }

    public boolean isStepByStep() {
        return stepByStep;
    }

    public void setStepByStep(boolean stepByStep) {
        this.stepByStep = stepByStep;
    }

    public long getCursor() {
        return cursor;
    }

    public void setCursor(long cursor) {
        this.cursor = cursor;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
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
