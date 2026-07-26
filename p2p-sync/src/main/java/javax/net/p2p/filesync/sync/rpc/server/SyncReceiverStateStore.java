package javax.net.p2p.filesync.sync.rpc.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.q3lives.ds.collections.DsHashMap;

public final class SyncReceiverStateStore implements AutoCloseable {

    private static final long DEFAULT_PENDING_EXPIRE_MILLIS = 300000L;

    private final DsHashMap completed;
    private final DsHashMap pendingUploadPathHash;
    private final DsHashMap pendingOwnerEventUidByPathKey;
    private final DsHashMap pendingOwnerLastModifiedByPathKey;
    private final DsHashMap pendingOwnerCreatedAtByPathKey;
    private final long pendingExpireMillis;

    public SyncReceiverStateStore(Path storeDir) {
        this(storeDir, DEFAULT_PENDING_EXPIRE_MILLIS);
    }

    public SyncReceiverStateStore(Path storeDir, long pendingExpireMillis) {
        try {
            Files.createDirectories(storeDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.pendingExpireMillis = pendingExpireMillis <= 0L ? DEFAULT_PENDING_EXPIRE_MILLIS : pendingExpireMillis;
        this.completed = new DsHashMap(storeDir.resolve("completed.map").toFile());
        this.completed.setSyncModeStrong100ms();
        this.pendingUploadPathHash = new DsHashMap(storeDir.resolve("pending_upload.map").toFile());
        this.pendingUploadPathHash.setSyncModeStrong100ms();
        this.pendingOwnerEventUidByPathKey = new DsHashMap(storeDir.resolve("pending_owner_by_path.map").toFile());
        this.pendingOwnerEventUidByPathKey.setSyncModeStrong100ms();
        this.pendingOwnerLastModifiedByPathKey = new DsHashMap(storeDir.resolve("pending_owner_last_modified_by_path.map").toFile());
        this.pendingOwnerLastModifiedByPathKey.setSyncModeStrong100ms();
        this.pendingOwnerCreatedAtByPathKey = new DsHashMap(storeDir.resolve("pending_owner_created_at_by_path.map").toFile());
        this.pendingOwnerCreatedAtByPathKey.setSyncModeStrong100ms();
        cleanupExpiredPending();
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

    public Long getPendingOwnerLastModified(long pathKey) {
        return pendingOwnerLastModifiedByPathKey.get(Long.valueOf(pathKey));
    }

    public boolean tryAcquirePendingPath(long pathKey, long eventUid, long lastModifiedMillis) {
        synchronized (this) {
            Long existing = pendingOwnerEventUidByPathKey.get(Long.valueOf(pathKey));
            if (existing != null && existing.longValue() != eventUid) {
                return false;
            }
            long now = System.currentTimeMillis();
            pendingOwnerEventUidByPathKey.put(Long.valueOf(pathKey), Long.valueOf(eventUid));
            pendingOwnerLastModifiedByPathKey.put(Long.valueOf(pathKey), Long.valueOf(lastModifiedMillis));
            pendingOwnerCreatedAtByPathKey.put(Long.valueOf(pathKey), Long.valueOf(now));
            pendingOwnerEventUidByPathKey.sync();
            pendingOwnerLastModifiedByPathKey.sync();
            pendingOwnerCreatedAtByPathKey.sync();
            return true;
        }
    }

    public Long forceAcquirePendingPath(long pathKey, long eventUid, long lastModifiedMillis) {
        synchronized (this) {
            Long existing = pendingOwnerEventUidByPathKey.get(Long.valueOf(pathKey));
            long now = System.currentTimeMillis();
            pendingOwnerEventUidByPathKey.put(Long.valueOf(pathKey), Long.valueOf(eventUid));
            pendingOwnerLastModifiedByPathKey.put(Long.valueOf(pathKey), Long.valueOf(lastModifiedMillis));
            pendingOwnerCreatedAtByPathKey.put(Long.valueOf(pathKey), Long.valueOf(now));
            pendingOwnerEventUidByPathKey.sync();
            pendingOwnerLastModifiedByPathKey.sync();
            pendingOwnerCreatedAtByPathKey.sync();
            return existing;
        }
    }

    public void releasePendingPathIfOwner(long pathKey, long eventUid) {
        synchronized (this) {
            Long existing = pendingOwnerEventUidByPathKey.get(Long.valueOf(pathKey));
            if (existing != null && existing.longValue() == eventUid) {
                pendingOwnerEventUidByPathKey.remove(Long.valueOf(pathKey));
                pendingOwnerLastModifiedByPathKey.remove(Long.valueOf(pathKey));
                pendingOwnerCreatedAtByPathKey.remove(Long.valueOf(pathKey));
                pendingOwnerEventUidByPathKey.sync();
                pendingOwnerLastModifiedByPathKey.sync();
                pendingOwnerCreatedAtByPathKey.sync();
            }
        }
    }

    public void cleanupExpiredPending() {
        synchronized (this) {
            long now = System.currentTimeMillis();
            ArrayList<Long> stalePathKeys = new ArrayList<Long>();
            ArrayList<Long> staleOwnerEventUids = new ArrayList<Long>();
            Set<Long> liveOwnerEventUids = new HashSet<Long>();
            for (Map.Entry<Long, Long> entry : pendingOwnerEventUidByPathKey.entrySet()) {
                Long pathKey = entry.getKey();
                Long ownerEventUid = entry.getValue();
                if (pathKey == null || ownerEventUid == null) {
                    continue;
                }
                Long createdAt = pendingOwnerCreatedAtByPathKey.get(pathKey);
                if (createdAt == null || now - createdAt.longValue() >= pendingExpireMillis) {
                    stalePathKeys.add(pathKey);
                    staleOwnerEventUids.add(ownerEventUid);
                    continue;
                }
                liveOwnerEventUids.add(ownerEventUid);
            }
            boolean changed = false;
            for (Long pathKey : stalePathKeys) {
                pendingOwnerEventUidByPathKey.remove(pathKey);
                pendingOwnerLastModifiedByPathKey.remove(pathKey);
                pendingOwnerCreatedAtByPathKey.remove(pathKey);
                changed = true;
            }
            for (Long eventUid : staleOwnerEventUids) {
                pendingUploadPathHash.remove(eventUid);
                changed = true;
            }
            ArrayList<Long> orphanPendingEventUids = new ArrayList<Long>();
            for (Map.Entry<Long, Long> entry : pendingUploadPathHash.entrySet()) {
                Long eventUid = entry.getKey();
                if (eventUid == null) {
                    continue;
                }
                if (!liveOwnerEventUids.contains(eventUid)) {
                    orphanPendingEventUids.add(eventUid);
                }
            }
            for (Long eventUid : orphanPendingEventUids) {
                pendingUploadPathHash.remove(eventUid);
                changed = true;
            }
            if (changed) {
                pendingUploadPathHash.sync();
                pendingOwnerEventUidByPathKey.sync();
                pendingOwnerLastModifiedByPathKey.sync();
                pendingOwnerCreatedAtByPathKey.sync();
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
        pendingOwnerLastModifiedByPathKey.sync();
        pendingOwnerLastModifiedByPathKey.close();
        pendingOwnerCreatedAtByPathKey.sync();
        pendingOwnerCreatedAtByPathKey.close();
    }
}
