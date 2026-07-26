package javax.net.p2p.filesync.sync;

import java.util.List;

public interface SyncUploadStatusProvider {

    List<SyncUploadStatus> snapshotActiveUploads(int limit);
}
