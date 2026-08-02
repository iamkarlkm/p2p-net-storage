package javax.net.p2p.filesync.sync.rpc.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.net.p2p.rpc.sync.proto.SyncEventAck;
import javax.net.p2p.rpc.sync.proto.SyncEventRequest;
import javax.net.p2p.rpc.sync.proto.SyncEventType;
import javax.net.p2p.rpc.sync.proto.SyncFinalizeRequest;
import javax.net.p2p.utils.SecurityUtils;
import javax.net.p2p.utils.XXHashUtil;

public final class SyncReceiverRpcService {

    private final int storeId;
    private final Path rootDir;
    private final SyncReceiverStateStore stateStore;
    private final SyncEventApplier applier;
    private final SyncConflictPolicy conflictPolicy;
    private static final String WRITE_CONFLICT = "write_conflict";

    public SyncReceiverRpcService(int storeId, Path rootDir, SyncReceiverStateStore stateStore, SyncEventApplier applier) {
        this(storeId, rootDir, stateStore, applier, SyncConflictPolicy.FAIL_FAST);
    }

    public SyncReceiverRpcService(int storeId, Path rootDir, SyncReceiverStateStore stateStore, SyncEventApplier applier, SyncConflictPolicy conflictPolicy) {
        this.storeId = storeId;
        this.rootDir = rootDir.toAbsolutePath().normalize();
        this.stateStore = stateStore;
        this.applier = applier;
        this.conflictPolicy = conflictPolicy == null ? SyncConflictPolicy.FAIL_FAST : conflictPolicy;
    }

    public SyncEventAck applyEvent(SyncEventRequest req) {
        if (req == null) {
            return SyncEventAck.newBuilder().setOk(false).setMessage("empty request").build();
        }
        stateStore.cleanupExpiredPending();
        long eventUid = req.getEventUid();
        if (stateStore.isCompleted(eventUid)) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(true)
                .setStoreId(storeId)
                .setMessage("duplicate")
                .build();
        }
        if (req.getDirectory() || req.getType() == SyncEventType.DELETE) {
            SyncEventAck ack = applier.apply(req);
            if (!ack.getOk()) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage(ack.getMessage())
                    .build();
            }
            stateStore.markCompleted(eventUid);
            stateStore.removePending(eventUid);
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(true)
                .setStoreId(storeId)
                .setMessage("ok")
                .build();
        }

        if (req.getType() != SyncEventType.CREATE && req.getType() != SyncEventType.MODIFY) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage("unsupported type")
                .build();
        }

        long pathHash = hashPath(req.getPath());
        Long existing = stateStore.getPendingPathHash(eventUid);
        if (existing == null) {
            stateStore.putPending(eventUid, pathHash);
        } else if (existing.longValue() != pathHash) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage("eventUid conflicts with another path")
                .build();
        }

        long pathKey = hashPathKey(req.getTaskId(), req.getPath());
        if (!stateStore.tryAcquirePendingPath(pathKey, eventUid, req.getLastModifiedMillis())) {
            if (conflictPolicy == SyncConflictPolicy.LAST_WRITE_WINS && takeoverPendingPath(req, pathKey, eventUid)) {
                // claimed by newer event
            } else {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage(WRITE_CONFLICT)
                .build();
            }
        }

        SyncEventRequest prepare = SyncEventRequest.newBuilder(req).setLastModifiedMillis(0L).build();
        SyncEventAck ack = applier.apply(prepare);
        if (!ack.getOk()) {
            stateStore.releasePendingPathIfOwner(pathKey, eventUid);
            stateStore.removePending(eventUid);
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage(ack.getMessage())
                .build();
        }

        return SyncEventAck.newBuilder()
            .setEventUid(eventUid)
            .setOk(true)
            .setNeedsUpload(true)
            .setStoreId(storeId)
            .setMessage("need_upload")
            .build();
    }

    public SyncEventAck finalizeEvent(SyncFinalizeRequest req) {
        if (req == null) {
            return SyncEventAck.newBuilder().setOk(false).setMessage("empty request").build();
        }
        stateStore.cleanupExpiredPending();
        long eventUid = req.getEventUid();
        if (stateStore.isCompleted(eventUid)) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(true)
                .setStoreId(storeId)
                .setMessage("duplicate")
                .build();
        }
        Long pendingHash = stateStore.getPendingPathHash(eventUid);
        if (pendingHash == null) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage("event is not pending")
                .build();
        }
        long pathHash = hashPath(req.getPath());
        if (pendingHash.longValue() != pathHash) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage("path mismatch")
                .build();
        }
        long pathKey = hashPathKey(req.getTaskId(), req.getPath());
        Long owner = stateStore.getPendingOwnerEventUid(pathKey);
        if (owner != null && owner.longValue() != eventUid) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage(WRITE_CONFLICT)
                .build();
        }
        String p = req.getPath() == null ? "" : req.getPath().replace('\\', '/');
        if (p.isBlank() || p.startsWith("/") || p.contains(":")) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage("invalid path")
                .build();
        }
        for (String seg : p.split("/")) {
            if (seg.equals(".") || seg.equals("..")) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage("path traversal is not allowed")
                    .build();
            }
        }
        Path target = rootDir.resolve(p).normalize();
        if (!target.startsWith(rootDir)) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage("path traversal is not allowed")
                .build();
        }
        if (req.getDirectory()) {
            if (!Files.isDirectory(target)) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage("directory not found")
                    .build();
            }
        } else {
            if (!Files.isRegularFile(target)) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage("file not found")
                    .build();
            }
            if (req.getContentLength() < 0L) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage("invalid content length")
                    .build();
            }
            if (req.getContentMd5() == null || req.getContentMd5().trim().isEmpty()) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage("missing content md5")
                    .build();
            }
            FileContentMetadata metadata;
            try {
                metadata = loadFileContentMetadata(target);
            } catch (Exception e) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage("failed to verify content")
                    .build();
            }
            if (metadata.length != req.getContentLength()) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage("content_length_mismatch")
                    .build();
            }
            if (!metadata.md5.equalsIgnoreCase(req.getContentMd5())) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage("content_checksum_mismatch")
                    .build();
            }
        }

        SyncEventAck ack = applier.apply(SyncEventRequest.newBuilder()
            .setTaskId(req.getTaskId())
            .setEventUid(eventUid)
            .setFileId(0L)
            .setPath(req.getPath())
            .setDirectory(req.getDirectory())
            .setType(req.getType())
            .setLastModifiedMillis(req.getLastModifiedMillis())
            .build());
        if (!ack.getOk()) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage(ack.getMessage())
                .build();
        }

        stateStore.removePending(eventUid);
        stateStore.releasePendingPathIfOwner(pathKey, eventUid);
        stateStore.markCompleted(eventUid);
        return SyncEventAck.newBuilder()
            .setEventUid(eventUid)
            .setOk(true)
            .setStoreId(storeId)
            .setMessage("ok")
            .build();
    }

    private static long hashPath(String path) {
        byte[] b = path == null ? new byte[0] : path.getBytes(StandardCharsets.UTF_8);
        return XXHashUtil.hash64(b);
    }

    private static FileContentMetadata loadFileContentMetadata(Path path) throws Exception {
        long length = Files.size(path);
        String md5 = SecurityUtils.getFileMD5String(path.toFile());
        if (md5 == null || md5.trim().isEmpty()) {
            throw new IllegalStateException("failed to compute file md5");
        }
        return new FileContentMetadata(length, md5);
    }

    private static long hashPathKey(long taskId, String path) {
        String p = path == null ? "" : path.replace('\\', '/');
        byte[] b = (taskId + "\n" + p).getBytes(StandardCharsets.UTF_8);
        return XXHashUtil.hash64(b);
    }

    private boolean takeoverPendingPath(SyncEventRequest req, long pathKey, long eventUid) {
        Long owner = stateStore.getPendingOwnerEventUid(pathKey);
        if (owner == null || owner.longValue() == eventUid) {
            return stateStore.tryAcquirePendingPath(pathKey, eventUid, req.getLastModifiedMillis());
        }
        Long ownerLastModified = stateStore.getPendingOwnerLastModified(pathKey);
        long incomingLastModified = req.getLastModifiedMillis();
        long currentLastModified = ownerLastModified == null ? Long.MIN_VALUE : ownerLastModified.longValue();
        if (incomingLastModified < currentLastModified) {
            return false;
        }
        Long displacedOwner = stateStore.forceAcquirePendingPath(pathKey, eventUid, incomingLastModified);
        if (displacedOwner != null && displacedOwner.longValue() != eventUid) {
            stateStore.removePending(displacedOwner.longValue());
        }
        return true;
    }

    private static final class FileContentMetadata {
        private final long length;
        private final String md5;

        private FileContentMetadata(long length, String md5) {
            this.length = length;
            this.md5 = md5;
        }
    }
}
