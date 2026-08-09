package javax.net.p2p.filesync.sync;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@FunctionalInterface
public interface FileSyncEventHandler {
    void handle(FileSyncEventType type, long fileId, String relativePath, Path absolutePath, boolean directory, FileSyncAcker acker);

    default void handleRename(FileSyncEventType type, long targetFileId, String targetRelativePath, Path targetAbsolutePath, String sourceRelativePath, boolean directory, FileSyncAcker acker) {
        if ((type == FileSyncEventType.RENAME || type == FileSyncEventType.MOVE)
            && sourceRelativePath != null && !sourceRelativePath.isEmpty()) {
            long sourceId = hashSourceFileId(sourceRelativePath);
            Path sourceAbs = targetAbsolutePath == null ? null
                : targetAbsolutePath.getParent().resolve(sourceRelativePath);
            final CountDownLatch both = new CountDownLatch(2);
            final AtomicBoolean anyFail = new AtomicBoolean(false);
            final AtomicReference<String> failMsg = new AtomicReference<>("");
            FileSyncAcker wrapped = new FileSyncAcker() {
                public void ack() { both.countDown(); }
                public void retry() {
                    anyFail.set(true);
                    failMsg.compareAndSet("", "retry");
                    both.countDown();
                }
                public void fail(String reason) {
                    anyFail.set(true);
                    failMsg.compareAndSet("", reason == null ? "fail" : reason);
                    both.countDown();
                }
            };
            handle(FileSyncEventType.DELETE, sourceId, sourceRelativePath, sourceAbs, directory, wrapped);
            handle(FileSyncEventType.CREATE, targetFileId, targetRelativePath, targetAbsolutePath, directory, wrapped);
            try { both.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (anyFail.get()) { acker.fail(failMsg.get()); } else { acker.ack(); }
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
