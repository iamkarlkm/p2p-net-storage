package javax.net.p2p.filesync.sync;

import java.util.List;

public interface SyncUploadStatusProvider {

    List<SyncUploadStatus> snapshotActiveUploads(int limit);

    List<SyncUploadStatus> snapshotRecentCompletedUploads(int limit);

    List<SyncUploadStatus> snapshotRecentFailedUploads(int limit);
}
