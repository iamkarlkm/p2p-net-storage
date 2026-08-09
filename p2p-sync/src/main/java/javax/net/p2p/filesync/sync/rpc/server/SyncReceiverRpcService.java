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
        SyncEventType t = req.getType();
        boolean renameKind = t == SyncEventType.RENAME || t == SyncEventType.MOVE;
        if (renameKind) {
            return applyRenameOrMoveEvent(req, eventUid);
        }
        if (req.getDirectory() || t == SyncEventType.DELETE) {
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

        if (t != SyncEventType.CREATE && t != SyncEventType.MODIFY) {
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

    private SyncEventAck applyRenameOrMoveEvent(SyncEventRequest req, long eventUid) {
        String sourcePath = req.getSourcePath();
        if (sourcePath == null || sourcePath.trim().isEmpty()) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage("rename/move requires source_path")
                .build();
        }
        String targetPath = req.getPath() == null ? "" : req.getPath();
        if (targetPath.trim().isEmpty()) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage("rename/move requires target path")
                .build();
        }
        String sp = sourcePath.replace('\\', '/');
        String tp = targetPath.replace('\\', '/');
        if (sp.isBlank() || sp.startsWith("/") || sp.contains(":") || tp.isBlank() || tp.startsWith("/") || tp.contains(":")) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage("invalid path")
                .build();
        }
        for (String seg : sp.split("/")) {
            if (seg.equals(".") || seg.equals("..")) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage("path traversal is not allowed")
                    .build();
            }
        }
        for (String seg : tp.split("/")) {
            if (seg.equals(".") || seg.equals("..")) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage("path traversal is not allowed")
                    .build();
            }
        }
        Path sourceAbs = rootDir.resolve(sp).normalize();
        Path targetAbs = rootDir.resolve(tp).normalize();
        if (!sourceAbs.startsWith(rootDir) || !targetAbs.startsWith(rootDir)) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage("path traversal is not allowed")
                .build();
        }
        if (req.getDirectory() && !Files.exists(sourceAbs)) {
            stateStore.markCompleted(eventUid);
            stateStore.removePending(eventUid);
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(true)
                .setStoreId(storeId)
                .setMessage("source_missing_skipping")
                .build();
        }
        long sourcePathKey = hashPathKey(req.getTaskId(), sp);
        long targetPathKey = hashPathKey(req.getTaskId(), tp);
        long sourcePathHash = hashPath(sp);
        if (!stateStore.tryAcquirePendingPath(sourcePathKey, eventUid, req.getLastModifiedMillis())) {
            if (!(conflictPolicy == SyncConflictPolicy.LAST_WRITE_WINS && takeoverPendingPath(req, sourcePathKey, eventUid))) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage(WRITE_CONFLICT)
                    .build();
            }
        }
        if (!stateStore.tryAcquirePendingPath(targetPathKey, eventUid, req.getLastModifiedMillis())) {
            if (!(conflictPolicy == SyncConflictPolicy.LAST_WRITE_WINS && takeoverPendingPath(req, targetPathKey, eventUid))) {
                stateStore.releasePendingPathIfOwner(sourcePathKey, eventUid);
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage(WRITE_CONFLICT)
                    .build();
            }
        }
        Long existing = stateStore.getPendingPathHash(eventUid);
        if (existing == null) {
            stateStore.putPending(eventUid, sourcePathHash);
        }
        if (req.getDirectory()) {
            SyncEventAck ack = applier.apply(req);
            if (!ack.getOk()) {
                stateStore.releasePendingPathIfOwner(sourcePathKey, eventUid);
                stateStore.releasePendingPathIfOwner(targetPathKey, eventUid);
                stateStore.removePending(eventUid);
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage(ack.getMessage())
                    .build();
            }
            stateStore.markCompleted(eventUid);
            stateStore.removePending(eventUid);
            stateStore.releasePendingPathIfOwner(sourcePathKey, eventUid);
            stateStore.releasePendingPathIfOwner(targetPathKey, eventUid);
            FileContentMetadata md = estimateDirectoryTreeMetadata(targetAbs, req.getLastModifiedMillis());
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(true)
                .setStoreId(storeId)
                .setMessage("ok")
                .setVerifiedContentLength(md.length)
                .setVerifiedContentMd5(md.md5 == null ? "" : md.md5)
                .build();
        }
        if (!Files.exists(sourceAbs)) {
            stateStore.releasePendingPathIfOwner(sourcePathKey, eventUid);
            stateStore.releasePendingPathIfOwner(targetPathKey, eventUid);
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage("source_missing")
                .build();
        }
        long sLen = -1L; long sMtime = -1L;
        try {
            sLen = Files.size(sourceAbs);
            sMtime = Files.getLastModifiedTime(sourceAbs).toMillis();
        } catch (Exception ignore) {}
        boolean needsUpload = false;
        if (req.getLastModifiedMillis() > 0L && sMtime > 0L && sLen >= 0L) {
            long tol = Math.max(1000L, Math.abs(req.getLastModifiedMillis() - sMtime));
            if (tol > 3000L) {
                needsUpload = true;
            }
            long tLen = Files.exists(targetAbs) ? readSizeQuiet(targetAbs) : -1L;
            if (tLen >= 0L && sLen >= 0L && tLen != sLen) {
                needsUpload = true;
            }
        }
        if (needsUpload) {
            SyncEventRequest prepare = SyncEventRequest.newBuilder(req).setLastModifiedMillis(0L).setPath(tp).setSourcePath(sp).build();
            SyncEventAck ack = applier.apply(prepare);
            if (!ack.getOk()) {
                stateStore.releasePendingPathIfOwner(sourcePathKey, eventUid);
                stateStore.releasePendingPathIfOwner(targetPathKey, eventUid);
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
        SyncEventAck ack = applier.apply(req);
        if (!ack.getOk()) {
            stateStore.releasePendingPathIfOwner(sourcePathKey, eventUid);
            stateStore.releasePendingPathIfOwner(targetPathKey, eventUid);
            stateStore.removePending(eventUid);
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage(ack.getMessage())
                .build();
        }
        stateStore.markCompleted(eventUid);
        stateStore.removePending(eventUid);
        stateStore.releasePendingPathIfOwner(sourcePathKey, eventUid);
        stateStore.releasePendingPathIfOwner(targetPathKey, eventUid);
        FileContentMetadata md;
        try {
            md = loadFileContentMetadata(targetAbs);
        } catch (Exception e) {
            md = new FileContentMetadata(-1L, null);
        }
        return SyncEventAck.newBuilder()
            .setEventUid(eventUid)
            .setOk(true)
            .setStoreId(storeId)
            .setMessage("ok")
            .setVerifiedContentLength(Math.max(-1L, md.length))
            .setVerifiedContentMd5(md.md5 == null ? "" : md.md5)
            .build();
    }

    private static long readSizeQuiet(Path p) {
        try { return Files.size(p); } catch (Exception e) { return -1L; }
    }

    private static FileContentMetadata estimateDirectoryTreeMetadata(Path dir, long lastModifiedMillis) {
        long totalLen = 0L;
        long count = 0L;
        try {
            if (Files.isDirectory(dir)) {
                try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
                    java.util.Iterator<Path> it = s.iterator();
                    while (it.hasNext()) {
                        Path p = it.next();
                        if (Files.isRegularFile(p)) {
                            try { totalLen += Files.size(p); count++; } catch (Exception ignore) {}
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
        String syntheticMd5 = null;
        if (lastModifiedMillis > 0L && count > 0L) {
            try {
                byte[] input = ("dir-" + totalLen + "-" + count + "-" + lastModifiedMillis)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(input);
                StringBuilder sb = new StringBuilder(digest.length * 2);
                for (byte b : digest) {
                    sb.append(String.format("%02x", Integer.valueOf(b & 0xFF)));
                }
                syntheticMd5 = sb.toString();
            } catch (Exception ignore) {}
        }
        return new FileContentMetadata(totalLen, syntheticMd5);
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
        SyncEventType t = req.getType();
        boolean renameKind = t == SyncEventType.RENAME || t == SyncEventType.MOVE;
        Long pendingHash = stateStore.getPendingPathHash(eventUid);
        if (pendingHash == null) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage("event is not pending")
                .build();
        }
        String p = req.getPath() == null ? "" : req.getPath().replace('\\', '/');
        long targetHash = hashPath(p);
        String sp = req.getSourcePath() == null ? "" : req.getSourcePath().replace('\\', '/');
        long sourceHash = hashPath(sp);
        boolean hashMatched = pendingHash.longValue() == targetHash
            || (renameKind && pendingHash.longValue() == sourceHash);
        if (!hashMatched) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage("path mismatch")
                .build();
        }
        long targetPathKey = hashPathKey(req.getTaskId(), p);
        long sourcePathKey = renameKind && !sp.isEmpty() ? hashPathKey(req.getTaskId(), sp) : -1L;
        Long ownerT = stateStore.getPendingOwnerEventUid(targetPathKey);
        if (ownerT != null && ownerT.longValue() != eventUid) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage(WRITE_CONFLICT)
                .build();
        }
        if (sourcePathKey >= 0L) {
            Long ownerS = stateStore.getPendingOwnerEventUid(sourcePathKey);
            if (ownerS != null && ownerS.longValue() != eventUid) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage(WRITE_CONFLICT)
                    .build();
            }
        }
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
        if (renameKind && !sp.isEmpty()) {
            if (sp.startsWith("/") || sp.contains(":")) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage("invalid source path")
                    .build();
            }
            for (String seg : sp.split("/")) {
                if (seg.equals(".") || seg.equals("..")) {
                    return SyncEventAck.newBuilder()
                        .setEventUid(eventUid)
                        .setOk(false)
                        .setStoreId(storeId)
                        .setMessage("path traversal is not allowed")
                        .build();
                }
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
        if (renameKind && !sp.isEmpty()) {
            Path sourceAbs = rootDir.resolve(sp).normalize();
            if (!sourceAbs.startsWith(rootDir)) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage("path traversal is not allowed")
                    .build();
            }
        }
        long verifiedContentLength = -1L;
        String verifiedContentMd5 = "";
        if (req.getDirectory()) {
            if (!Files.isDirectory(target)) {
                return SyncEventAck.newBuilder()
                    .setEventUid(eventUid)
                    .setOk(false)
                    .setStoreId(storeId)
                    .setMessage("directory not found")
                    .build();
            }
            FileContentMetadata md = estimateDirectoryTreeMetadata(target, req.getLastModifiedMillis());
            verifiedContentLength = md.length;
            verifiedContentMd5 = md.md5 == null ? "" : md.md5;
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
            verifiedContentLength = metadata.length;
            verifiedContentMd5 = metadata.md5;
        }

        if (sp != null && !sp.isEmpty()) {
            try {
                Path sourceAbs = rootDir.resolve(sp).normalize();
                if (sourceAbs.startsWith(rootDir) && Files.exists(sourceAbs)) {
                    try {
                        Files.deleteIfExists(sourceAbs);
                    } catch (Exception delEx) {
                        try { Files.delete(sourceAbs); } catch (Exception ignore2) {}
                    }
                }
            } catch (Exception ignore) {
            }
        }
        SyncEventRequest.Builder applyReq = SyncEventRequest.newBuilder()
            .setTaskId(req.getTaskId())
            .setEventUid(eventUid)
            .setFileId(0L)
            .setPath(req.getPath())
            .setDirectory(req.getDirectory())
            .setType(req.getType())
            .setLastModifiedMillis(req.getLastModifiedMillis());
        if (renameKind && !sp.isEmpty()) {
            applyReq.setSourcePath(req.getSourcePath());
        }
        SyncEventAck ack = applier.apply(applyReq.build());
        if (!ack.getOk()) {
            return SyncEventAck.newBuilder()
                .setEventUid(eventUid)
                .setOk(false)
                .setStoreId(storeId)
                .setMessage(ack.getMessage())
                .build();
        }

        stateStore.removePending(eventUid);
        stateStore.releasePendingPathIfOwner(targetPathKey, eventUid);
        if (sourcePathKey >= 0L) {
            stateStore.releasePendingPathIfOwner(sourcePathKey, eventUid);
        }
        stateStore.markCompleted(eventUid);
        return SyncEventAck.newBuilder()
            .setEventUid(eventUid)
            .setOk(true)
            .setStoreId(storeId)
            .setMessage("ok")
            .setVerifiedContentLength(verifiedContentLength)
            .setVerifiedContentMd5(verifiedContentMd5)
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
