package javax.net.p2p.filesync.sync.rpc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.filesync.sync.FileSyncAcker;
import javax.net.p2p.filesync.sync.FileSyncEventHandler;
import javax.net.p2p.filesync.sync.FileSyncEventType;
import javax.net.p2p.filesync.sync.SyncUploadStatus;
import javax.net.p2p.filesync.sync.SyncUploadStatusProvider;
import javax.net.p2p.interfaces.P2PFileService;
import javax.net.p2p.rpc.api.RpcClient;
import javax.net.p2p.rpc.model.RpcCallOptions;
import javax.net.p2p.rpc.server.SyncRpcServices;
import javax.net.p2p.rpc.sync.proto.SyncEventAck;
import javax.net.p2p.rpc.sync.proto.SyncEventRequest;
import javax.net.p2p.rpc.sync.proto.SyncEventType;
import javax.net.p2p.rpc.sync.proto.SyncFinalizeRequest;
import javax.net.p2p.utils.FileUtil;
import javax.net.p2p.utils.SecurityUtils;
import javax.net.p2p.utils.XXHashUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class RpcSyncEventHandler implements FileSyncEventHandler, SyncUploadStatusProvider, AutoCloseable {

    private final RpcClient rpcClient;
    private final P2PFileService fileClient;
    private final long taskId;
    private final ExecutorService uploadExecutor;
    private final ScheduledExecutorService uploadProgressExecutor;
    private final Map<Long, UploadStatusEntry> activeUploads = new ConcurrentHashMap<Long, UploadStatusEntry>();
    private final Object historyLock = new Object();
    private final Deque<SyncUploadStatus> recentCompletedUploads = new ArrayDeque<SyncUploadStatus>();
    private final Deque<SyncUploadStatus> recentFailedUploads = new ArrayDeque<SyncUploadStatus>();
    private static final String WRITE_CONFLICT = "write_conflict";
    private static final int MAX_RECENT_HISTORY = 20;

    public RpcSyncEventHandler(RpcClient rpcClient, P2PFileService fileClient, long taskId) {
        this.rpcClient = Objects.requireNonNull(rpcClient, "rpcClient");
        this.fileClient = Objects.requireNonNull(fileClient, "fileClient");
        this.taskId = taskId;
        this.uploadExecutor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private int idx = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "p2p-sync-upload-" + (++idx));
                t.setDaemon(true);
                return t;
            }
        });
        this.uploadProgressExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "p2p-sync-upload-progress");
                t.setDaemon(true);
                return t;
            }
        });
    }

    @Override
    public void close() {
        uploadExecutor.shutdownNow();
        uploadProgressExecutor.shutdownNow();
        activeUploads.clear();
        synchronized (historyLock) {
            recentCompletedUploads.clear();
            recentFailedUploads.clear();
        }
    }

    @Override
    public List<SyncUploadStatus> snapshotActiveUploads(int limit) {
        List<UploadStatusEntry> entries = new ArrayList<UploadStatusEntry>(activeUploads.values());
        java.util.Collections.sort(entries, new Comparator<UploadStatusEntry>() {
            @Override
            public int compare(UploadStatusEntry o1, UploadStatusEntry o2) {
                if (o1.startedAtMillis == o2.startedAtMillis) {
                    return o1.path.compareTo(o2.path);
                }
                return o1.startedAtMillis < o2.startedAtMillis ? -1 : 1;
            }
        });
        List<SyncUploadStatus> out = new ArrayList<SyncUploadStatus>();
        int max = limit <= 0 ? Integer.MAX_VALUE : limit;
        for (UploadStatusEntry entry : entries) {
            if (out.size() >= max) {
                break;
            }
            out.add(entry.snapshot());
        }
        return out;
    }

    @Override
    public List<SyncUploadStatus> snapshotRecentCompletedUploads(int limit) {
        return snapshotRecentHistory(recentCompletedUploads, limit);
    }

    @Override
    public List<SyncUploadStatus> snapshotRecentFailedUploads(int limit) {
        return snapshotRecentHistory(recentFailedUploads, limit);
    }

    @Override
    public void handle(FileSyncEventType type, long fileId, String relativePath, Path absolutePath, boolean directory, FileSyncAcker acker) {
        // 严格 ACK：事件已转入 inflight，此处只负责发起 RPC，并在回调里 ack/retry
        long lastModifiedMillis = 0L;
        if (!directory && absolutePath != null) {
            try {
                lastModifiedMillis = Files.getLastModifiedTime(absolutePath).toMillis();
            } catch (Exception ignored) {
            }
        }
        final long lastModifiedMillisFinal = lastModifiedMillis;

        long eventUid = computeEventUid(taskId, fileId, type, directory, lastModifiedMillisFinal, relativePath);
        // eventUid 用于对端幂等：同一个事件重发必须保持一致
        SyncEventRequest req = SyncEventRequest.newBuilder()
            .setTaskId(taskId)
            .setEventUid(eventUid)
            .setFileId(fileId)
            .setPath(relativePath == null ? "" : relativePath)
            .setDirectory(directory)
            .setType(toProtoType(type))
            .setLastModifiedMillis(lastModifiedMillisFinal)
            .build();

        RpcCallOptions options = RpcCallOptions.withDeadline(System.currentTimeMillis() + 5_000).withIdempotent(true);
        rpcClient.unaryAsync(SyncRpcServices.SYNC_SERVICE, SyncRpcServices.APPLY_EVENT, req, SyncEventAck.class, options)
            .whenComplete((resp, ex) -> {
                if (ex != null || resp == null) {
                    acker.retry();
                    return;
                }
                if (!resp.getOk() || resp.getEventUid() != eventUid) {
                    if (resp != null && !resp.getOk() && isWriteConflict(resp.getMessage())) {
                        log.error("p2p-sync write conflict: phase=apply, taskId={}, fileId={}, path={}, eventUid={}, msg={}",
                            taskId, fileId, relativePath, eventUid, resp.getMessage());
                        acker.fail(resp.getMessage());
                        return;
                    }
                    acker.retry();
                    return;
                }
                if (!resp.getNeedsUpload()) {
                    acker.ack();
                    return;
                }
                if (directory || type == FileSyncEventType.DELETE || absolutePath == null) {
                    acker.retry();
                    return;
                }
                uploadAndFinalize(resp.getStoreId(), eventUid, fileId, type, relativePath, absolutePath, lastModifiedMillisFinal, acker);
            });
    }

    private void uploadAndFinalize(int storeId, long eventUid, long fileId, FileSyncEventType type, String relativePath, Path absolutePath, long lastModifiedMillis, FileSyncAcker acker) {
        // 两阶段：ApplyEvent 通过 -> 上传文件内容 -> FinalizeEvent 才算最终 ACK
        final UploadStatusEntry statusEntry;
        try {
            int resumedSegments = segmentedUploadedSegments(storeId, relativePath);
            statusEntry = new UploadStatusEntry(storeId, eventUid, fileId, relativePath, Files.size(absolutePath), resumedSegments);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        activeUploads.put(Long.valueOf(eventUid), statusEntry);
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            ScheduledFuture<?> progressFuture = startUploadProgress(statusEntry);
            try {
                FileContentMetadata metadata = loadFileContentMetadata(absolutePath);
                statusEntry.setPhase("uploading");
                fileClient.putFileData(storeId, relativePath, absolutePath.toFile());
                refreshUploadProgress(statusEntry);
                statusEntry.markUploaded();
                statusEntry.setPhase("checking");
                if (!fileClient.checkWithMd5(storeId, relativePath, metadata.getLength(), metadata.getMd5())) {
                    throw new IllegalStateException("remote content check failed");
                }
                return metadata;
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                if (progressFuture != null) {
                    progressFuture.cancel(true);
                }
            }
        }, uploadExecutor).thenCompose(metadata -> {
            long finalizeLastModifiedMillis = resolveLastModifiedMillis(absolutePath, lastModifiedMillis);
            statusEntry.setPhase("finalizing");
            log.info("p2p-sync finalize dispatch: taskId={}, path={}, eventUid={}, lastModifiedMillis={}",
                taskId, relativePath, eventUid, finalizeLastModifiedMillis);
            SyncFinalizeRequest fin = SyncFinalizeRequest.newBuilder()
                .setTaskId(taskId)
                .setEventUid(eventUid)
                .setPath(relativePath == null ? "" : relativePath)
                .setDirectory(false)
                .setType(toProtoType(type))
                .setLastModifiedMillis(finalizeLastModifiedMillis)
                .setContentLength(metadata.getLength())
                .setContentMd5(metadata.getMd5())
                .build();
            RpcCallOptions options = RpcCallOptions.withDeadline(System.currentTimeMillis() + 10_000).withIdempotent(true);
            return rpcClient.unaryAsync(SyncRpcServices.SYNC_SERVICE, SyncRpcServices.FINALIZE_EVENT, fin, SyncEventAck.class, options);
        }).whenComplete((ack, ex) -> {
            if (ex != null || ack == null) {
                activeUploads.remove(Long.valueOf(eventUid));
                recordFailed(statusEntry, buildFailureMessage(ex, ack == null ? null : ack.getMessage()));
                log.error("p2p-sync finalize failed: taskId={}, path={}, eventUid={}, ackNull={}, ex={}",
                    taskId, relativePath, eventUid, ack == null, ex == null ? "null" : ex.toString());
                acker.retry();
                return;
            }
            if (ack.getOk() && ack.getEventUid() == eventUid) {
                activeUploads.remove(Long.valueOf(eventUid));
                recordCompleted(statusEntry);
                log.info("p2p-sync finalize acked: taskId={}, path={}, eventUid={}", taskId, relativePath, eventUid);
                acker.ack();
                return;
            }
            if (!ack.getOk() && isWriteConflict(ack.getMessage())) {
                activeUploads.remove(Long.valueOf(eventUid));
                recordFailed(statusEntry, ack.getMessage());
                log.error("p2p-sync write conflict: phase=finalize, taskId={}, path={}, eventUid={}, msg={}",
                    taskId, relativePath, eventUid, ack.getMessage());
                acker.fail(ack.getMessage());
                return;
            }
            activeUploads.remove(Long.valueOf(eventUid));
            recordFailed(statusEntry, buildFailureMessage(null, ack.getMessage()));
            acker.retry();
        });
    }

    private List<SyncUploadStatus> snapshotRecentHistory(Deque<SyncUploadStatus> history, int limit) {
        synchronized (historyLock) {
            List<SyncUploadStatus> out = new ArrayList<SyncUploadStatus>();
            int max = limit <= 0 ? Integer.MAX_VALUE : limit;
            for (SyncUploadStatus status : history) {
                if (out.size() >= max) {
                    break;
                }
                out.add(status);
            }
            return out;
        }
    }

    private void recordCompleted(UploadStatusEntry entry) {
        entry.setPhase("completed");
        addHistory(recentCompletedUploads, entry.snapshot(null));
    }

    private void recordFailed(UploadStatusEntry entry, String message) {
        entry.setPhase("failed");
        addHistory(recentFailedUploads, entry.snapshot(message));
    }

    private void addHistory(Deque<SyncUploadStatus> history, SyncUploadStatus status) {
        synchronized (historyLock) {
            history.addFirst(status);
            while (history.size() > MAX_RECENT_HISTORY) {
                history.removeLast();
            }
        }
    }

    private ScheduledFuture<?> startUploadProgress(final UploadStatusEntry entry) {
        if (!entry.segmented) {
            return null;
        }
        refreshUploadProgress(entry);
        return uploadProgressExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                refreshUploadProgress(entry);
            }
        }, 0L, 200L, TimeUnit.MILLISECONDS);
    }

    private static void refreshUploadProgress(UploadStatusEntry entry) {
        if (!entry.segmented) {
            return;
        }
        try {
            int uploadedSegments = FileUtil.getUpInfoTmp(entry.storeId, entry.path).getRight().size();
            entry.setUploadedSegments(uploadedSegments);
        } catch (Exception ignored) {
        }
    }

    private static int segmentedUploadedSegments(int storeId, String path) {
        if (path == null || path.trim().isEmpty()) {
            return 0;
        }
        try {
            return FileUtil.getUpInfoTmp(storeId, path).getRight().size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static FileContentMetadata loadFileContentMetadata(Path absolutePath) throws Exception {
        long length = Files.size(absolutePath);
        String md5 = SecurityUtils.getFileMD5String(absolutePath.toFile());
        if (md5 == null || md5.trim().isEmpty()) {
            throw new IllegalStateException("failed to compute file md5");
        }
        return new FileContentMetadata(length, md5);
    }

    private static long resolveLastModifiedMillis(Path absolutePath, long fallback) {
        try {
            return Files.getLastModifiedTime(absolutePath).toMillis();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean isWriteConflict(String msg) {
        return msg != null && (msg.equals(WRITE_CONFLICT) || msg.startsWith(WRITE_CONFLICT + ":"));
    }

    private static String buildFailureMessage(Throwable ex, String fallback) {
        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback;
        }
        if (ex == null) {
            return "upload_failed";
        }
        String msg = ex.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return ex.getClass().getSimpleName();
        }
        return msg;
    }

    private static SyncEventType toProtoType(FileSyncEventType type) {
        if (type == FileSyncEventType.CREATE) {
            return SyncEventType.CREATE;
        }
        if (type == FileSyncEventType.MODIFY) {
            return SyncEventType.MODIFY;
        }
        return SyncEventType.DELETE;
    }

    private static long computeEventUid(long taskId, long fileId, FileSyncEventType type, boolean directory, long lastModifiedMillis, String relativePath) {
        byte[] pathBytes = relativePath == null ? new byte[0] : relativePath.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + 8 + 4 + 1 + 8 + 4 + pathBytes.length);
        buf.putLong(taskId);
        buf.putLong(fileId);
        buf.putInt(type.ordinal());
        buf.put((byte) (directory ? 1 : 0));
        buf.putLong(lastModifiedMillis);
        buf.putInt(pathBytes.length);
        buf.put(pathBytes);
        return XXHashUtil.hash64(buf.array());
    }

    private static final class FileContentMetadata {
        private final long length;
        private final String md5;

        private FileContentMetadata(long length, String md5) {
            this.length = length;
            this.md5 = md5;
        }

        private long getLength() {
            return length;
        }

        private String getMd5() {
            return md5;
        }
    }

    private static final class UploadStatusEntry {
        private final int storeId;
        private final long eventUid;
        private final long fileId;
        private final String path;
        private final long fileSize;
        private final boolean segmented;
        private final int totalSegments;
        private final int resumedSegments;
        private final long startedAtMillis;
        private volatile String phase;
        private final AtomicInteger uploadedSegments = new AtomicInteger();
        private volatile long updatedAtMillis;
        private volatile long lastProgressAtMillis;

        private UploadStatusEntry(int storeId, long eventUid, long fileId, String path, long fileSize, int resumedSegments) {
            this.storeId = storeId;
            this.eventUid = eventUid;
            this.fileId = fileId;
            this.path = path == null ? "" : path;
            this.fileSize = fileSize;
            this.segmented = fileSize > P2PConfig.DATA_PUT_BLOCK_SIZE;
            this.totalSegments = segmentCount(fileSize);
            int normalizedResumedSegments = resumedSegments;
            if (normalizedResumedSegments < 0) {
                normalizedResumedSegments = 0;
            }
            if (this.totalSegments > 0 && normalizedResumedSegments > this.totalSegments) {
                normalizedResumedSegments = this.totalSegments;
            }
            this.resumedSegments = this.segmented ? normalizedResumedSegments : 0;
            this.startedAtMillis = System.currentTimeMillis();
            this.updatedAtMillis = this.startedAtMillis;
            this.lastProgressAtMillis = this.startedAtMillis;
            this.phase = "queued";
            this.uploadedSegments.set(this.resumedSegments);
        }

        private void setPhase(String phase) {
            this.phase = phase;
            this.updatedAtMillis = System.currentTimeMillis();
        }

        private void setUploadedSegments(int value) {
            int capped = value;
            if (capped < 0) {
                capped = 0;
            }
            if (totalSegments > 0 && capped > totalSegments) {
                capped = totalSegments;
            }
            int previous = this.uploadedSegments.get();
            this.uploadedSegments.set(capped);
            long now = System.currentTimeMillis();
            if (capped > previous) {
                this.lastProgressAtMillis = now;
            }
            this.updatedAtMillis = now;
        }

        private void markUploaded() {
            long now = System.currentTimeMillis();
            if (totalSegments > 0) {
                this.uploadedSegments.set(totalSegments);
            }
            this.lastProgressAtMillis = now;
            this.updatedAtMillis = now;
        }

        private SyncUploadStatus snapshot() {
            return snapshot(null);
        }

        private SyncUploadStatus snapshot(String message) {
            return new SyncUploadStatus(eventUid, fileId, path, phase, fileSize, segmented,
                totalSegments, uploadedSegments.get(), startedAtMillis, updatedAtMillis, lastProgressAtMillis, resumedSegments, null, message);
        }

        private static int segmentCount(long fileSize) {
            if (fileSize <= 0L) {
                return 1;
            }
            long count = fileSize / P2PConfig.DATA_PUT_BLOCK_SIZE;
            if (fileSize % P2PConfig.DATA_PUT_BLOCK_SIZE != 0L) {
                count++;
            }
            if (count > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) count;
        }
    }
}
