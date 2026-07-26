package javax.net.p2p.filesync.sync.rpc.server;

import com.q3lives.ds.collections.DsHashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SyncReceiverStateStore implements AutoCloseable {

    private final DsHashMap completed;
    private final DsHashMap pendingUploadPathHash;
    private final DsHashMap pendingOwnerEventUidByPathKey;

    public SyncReceiverStateStore(Path storeDir) {
        try {
            Files.createDirectories(storeDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.completed = new DsHashMap(storeDir.resolve("completed.map").toFile());
        this.completed.setSyncModeStrong100ms();
        this.pendingUploadPathHash = new DsHashMap(storeDir.resolve("pending_upload.map").toFile());
        this.pendingUploadPathHash.setSyncModeStrong100ms();
        this.pendingOwnerEventUidByPathKey = new DsHashMap(storeDir.resolve("pending_owner_by_path.map").toFile());
        this.pendingOwnerEventUidByPathKey.setSyncModeStrong100ms();
    }

    public boolean isCompleted(long eventUid) {
        return completed.get(Long.valueOf(eventUid)) != null;
    }

    public void markCompleted(long eventUid) {
        completed.put(Long.valueOf(eventUid), Long.valueOf(1L));
        completed.sync();
    }

    public boolean isPending(long eventUid) {
        return pendingUploadPathHash.get(Long.valueOf(eventUid)) != null;
    }

    public Long getPendingPathHash(long eventUid) {
        return pendingUploadPathHash.get(Long.valueOf(eventUid));
    }

    public void putPending(long eventUid, long pathHash) {
        pendingUploadPathHash.put(Long.valueOf(eventUid), Long.valueOf(pathHash));
        pendingUploadPathHash.sync();
    }

    public void removePending(long eventUid) {
        pendingUploadPathHash.remove(Long.valueOf(eventUid));
        pendingUploadPathHash.sync();
    }

    public Long getPendingOwnerEventUid(long pathKey) {
        return pendingOwnerEventUidByPathKey.get(Long.valueOf(pathKey));
    }

    public boolean tryAcquirePendingPath(long pathKey, long eventUid) {
        synchronized (this) {
            Long existing = pendingOwnerEventUidByPathKey.get(Long.valueOf(pathKey));
            if (existing != null && existing.longValue() != eventUid) {
                return false;
            }
            pendingOwnerEventUidByPathKey.put(Long.valueOf(pathKey), Long.valueOf(eventUid));
            pendingOwnerEventUidByPathKey.sync();
            return true;
        }
    }

    public void releasePendingPathIfOwner(long pathKey, long eventUid) {
        synchronized (this) {
            Long existing = pendingOwnerEventUidByPathKey.get(Long.valueOf(pathKey));
            if (existing != null && existing.longValue() == eventUid) {
                pendingOwnerEventUidByPathKey.remove(Long.valueOf(pathKey));
                pendingOwnerEventUidByPathKey.sync();
            }
        }
    }

    @Override
    public void close() {
        completed.sync();
        completed.close();
        pendingUploadPathHash.sync();
        pendingUploadPathHash.close();
        pendingOwnerEventUidByPathKey.sync();
        pendingOwnerEventUidByPathKey.close();
    }
}
