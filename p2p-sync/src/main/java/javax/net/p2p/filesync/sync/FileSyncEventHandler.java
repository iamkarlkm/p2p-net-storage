package javax.net.p2p.filesync.sync;

import java.nio.file.Path;

@FunctionalInterface
public interface FileSyncEventHandler {
    void handle(FileSyncEventType type, long fileId, String relativePath, Path absolutePath, boolean directory, FileSyncAcker acker);

    default void bindStateStore(P2PSyncStateStore store) {
    }
}
