package javax.net.p2p.filesync.sync;

import java.nio.file.Path;

@FunctionalInterface
public interface FileSyncEventHandler {
    void handle(FileSyncEventType type, long fileId, String relativePath, Path absolutePath, boolean directory, FileSyncAcker acker);

    default void handleRename(FileSyncEventType type, long targetFileId, String targetRelativePath, Path targetAbsolutePath, String sourceRelativePath, boolean directory, FileSyncAcker acker) {
        if (type == FileSyncEventType.RENAME || type == FileSyncEventType.MOVE) {
            long sourceId = sourceRelativePath == null || sourceRelativePath.isEmpty()
                ? targetFileId : hashSourceFileId(sourceRelativePath);
            handle(type, targetFileId, targetRelativePath, targetAbsolutePath, directory, acker);
        } else {
            handle(type, targetFileId, targetRelativePath, targetAbsolutePath, directory, acker);
        }
    }

    private static long hashSourceFileId(String sourceRelativePath) {
        byte[] b = (sourceRelativePath == null ? "" : sourceRelativePath).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return javax.net.p2p.utils.XXHashUtil.hash64(b);
    }

    default void bindStateStore(P2PSyncStateStore store) {
    }
}
