package javax.net.p2p.filesync.sync;

public final class SyncUploadStatus {

    private final long eventUid;
    private final long fileId;
    private final String path;
    private final String phase;
    private final long fileSize;
    private final boolean segmented;
    private final int totalSegments;
    private final int uploadedSegments;
    private final long startedAtMillis;
    private final long updatedAtMillis;

    public SyncUploadStatus(long eventUid, long fileId, String path, String phase, long fileSize,
        boolean segmented, int totalSegments, int uploadedSegments, long startedAtMillis, long updatedAtMillis) {
        this.eventUid = eventUid;
        this.fileId = fileId;
        this.path = path;
        this.phase = phase;
        this.fileSize = fileSize;
        this.segmented = segmented;
        this.totalSegments = totalSegments;
        this.uploadedSegments = uploadedSegments;
        this.startedAtMillis = startedAtMillis;
        this.updatedAtMillis = updatedAtMillis;
    }

    public long getEventUid() {
        return eventUid;
    }

    public long getFileId() {
        return fileId;
    }

    public String getPath() {
        return path;
    }

    public String getPhase() {
        return phase;
    }

    public long getFileSize() {
        return fileSize;
    }

    public boolean isSegmented() {
        return segmented;
    }

    public int getTotalSegments() {
        return totalSegments;
    }

    public int getUploadedSegments() {
        return uploadedSegments;
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }
}
