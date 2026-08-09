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
    private final long lastProgressAtMillis;
    private final int resumedSegments;
    private final String replicaLabel;
    private final String message;
    private final long verifiedContentLength;
    private final String verifiedContentMd5;
    private final String sourcePath;

    public SyncUploadStatus(long eventUid, long fileId, String path, String phase, long fileSize,
        boolean segmented, int totalSegments, int uploadedSegments, long startedAtMillis, long updatedAtMillis) {
        this(eventUid, fileId, path, phase, fileSize, segmented, totalSegments, uploadedSegments,
            startedAtMillis, updatedAtMillis, updatedAtMillis, 0, null, null, -1L, null, null);
    }

    public SyncUploadStatus(long eventUid, long fileId, String path, String phase, long fileSize,
        boolean segmented, int totalSegments, int uploadedSegments, long startedAtMillis, long updatedAtMillis,
        String message) {
        this(eventUid, fileId, path, phase, fileSize, segmented, totalSegments, uploadedSegments,
            startedAtMillis, updatedAtMillis, updatedAtMillis, 0, null, message, -1L, null, null);
    }

    public SyncUploadStatus(long eventUid, long fileId, String path, String phase, long fileSize,
        boolean segmented, int totalSegments, int uploadedSegments, long startedAtMillis, long updatedAtMillis,
        String replicaLabel, String message) {
        this(eventUid, fileId, path, phase, fileSize, segmented, totalSegments, uploadedSegments,
            startedAtMillis, updatedAtMillis, updatedAtMillis, 0, replicaLabel, message, -1L, null, null);
    }

    public SyncUploadStatus(long eventUid, long fileId, String path, String phase, long fileSize,
        boolean segmented, int totalSegments, int uploadedSegments, long startedAtMillis, long updatedAtMillis,
        long lastProgressAtMillis, int resumedSegments, String replicaLabel, String message) {
        this(eventUid, fileId, path, phase, fileSize, segmented, totalSegments, uploadedSegments,
            startedAtMillis, updatedAtMillis, lastProgressAtMillis, resumedSegments, replicaLabel, message, -1L, null, null);
    }

    public SyncUploadStatus(long eventUid, long fileId, String path, String phase, long fileSize,
        boolean segmented, int totalSegments, int uploadedSegments, long startedAtMillis, long updatedAtMillis,
        long lastProgressAtMillis, int resumedSegments, String replicaLabel, String message,
        long verifiedContentLength, String verifiedContentMd5, String sourcePath) {
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
        this.lastProgressAtMillis = lastProgressAtMillis;
        this.resumedSegments = resumedSegments;
        this.replicaLabel = replicaLabel;
        this.message = message;
        this.verifiedContentLength = verifiedContentLength;
        this.verifiedContentMd5 = verifiedContentMd5;
        this.sourcePath = sourcePath;
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

    public long getLastProgressAtMillis() {
        return lastProgressAtMillis;
    }

    public int getResumedSegments() {
        return resumedSegments;
    }

    public boolean isResumedUpload() {
        return resumedSegments > 0;
    }

    public String getReplicaLabel() {
        return replicaLabel;
    }

    public String getMessage() {
        return message;
    }

    public long getVerifiedContentLength() {
        return verifiedContentLength;
    }

    public String getVerifiedContentMd5() {
        return verifiedContentMd5;
    }

    public String getSourcePath() {
        return sourcePath;
    }
}
