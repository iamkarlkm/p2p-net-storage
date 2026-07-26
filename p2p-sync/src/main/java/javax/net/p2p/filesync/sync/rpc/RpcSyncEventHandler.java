package javax.net.p2p.filesync.sync.rpc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import javax.net.p2p.filesync.sync.FileSyncAcker;
import javax.net.p2p.filesync.sync.FileSyncEventHandler;
import javax.net.p2p.filesync.sync.FileSyncEventType;
import javax.net.p2p.interfaces.P2PFileService;
import javax.net.p2p.rpc.api.RpcClient;
import javax.net.p2p.rpc.model.RpcCallOptions;
import javax.net.p2p.rpc.server.SyncRpcServices;
import javax.net.p2p.rpc.sync.proto.SyncEventAck;
import javax.net.p2p.rpc.sync.proto.SyncEventRequest;
import javax.net.p2p.rpc.sync.proto.SyncEventType;
import javax.net.p2p.rpc.sync.proto.SyncFinalizeRequest;
import javax.net.p2p.utils.SecurityUtils;
import javax.net.p2p.utils.XXHashUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class RpcSyncEventHandler implements FileSyncEventHandler, AutoCloseable {

    private final RpcClient rpcClient;
    private final P2PFileService fileClient;
    private final long taskId;
    private final ExecutorService uploadExecutor;
    private static final String WRITE_CONFLICT = "write_conflict";

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
    }

    @Override
    public void close() {
        uploadExecutor.shutdownNow();
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
                uploadAndFinalize(resp.getStoreId(), eventUid, type, relativePath, absolutePath, lastModifiedMillisFinal, acker);
            });
    }

    private void uploadAndFinalize(int storeId, long eventUid, FileSyncEventType type, String relativePath, Path absolutePath, long lastModifiedMillis, FileSyncAcker acker) {
        // 两阶段：ApplyEvent 通过 -> 上传文件内容 -> FinalizeEvent 才算最终 ACK
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                FileContentMetadata metadata = loadFileContentMetadata(absolutePath);
                fileClient.putFileData(storeId, relativePath, absolutePath.toFile());
                if (!fileClient.checkWithMd5(storeId, relativePath, metadata.getLength(), metadata.getMd5())) {
                    throw new IllegalStateException("remote content check failed");
                }
                return metadata;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, uploadExecutor).thenCompose(metadata -> {
            long finalizeLastModifiedMillis = resolveLastModifiedMillis(absolutePath, lastModifiedMillis);
            log.info("p2p-sync finalize dispatch: taskId={}, path={}, eventUid={}, lastModifiedMillis={}",
                taskId, relativePath, eventUid, finalizeLastModifiedMillis);
            SyncFinalizeRequest fin = SyncFinalizeRequest.newBuilder()
                .setTaskId(taskId)
                .setEventUid(eventUid)
                .setPath(relativePath == null ? "" : relativePath)
                .setDirectory(false)
                .setType(toProtoType(type))
                .setLastModifiedMillis(finalizeLastModifiedMillis)
                .build();
            RpcCallOptions options = RpcCallOptions.withDeadline(System.currentTimeMillis() + 10_000).withIdempotent(true);
            return rpcClient.unaryAsync(SyncRpcServices.SYNC_SERVICE, SyncRpcServices.FINALIZE_EVENT, fin, SyncEventAck.class, options);
        }).whenComplete((ack, ex) -> {
            if (ex != null || ack == null) {
                log.error("p2p-sync finalize failed: taskId={}, path={}, eventUid={}, ackNull={}, ex={}",
                    taskId, relativePath, eventUid, ack == null, ex == null ? "null" : ex.toString());
                acker.retry();
                return;
            }
            if (ack.getOk() && ack.getEventUid() == eventUid) {
                log.info("p2p-sync finalize acked: taskId={}, path={}, eventUid={}", taskId, relativePath, eventUid);
                acker.ack();
                return;
            }
            if (!ack.getOk() && isWriteConflict(ack.getMessage())) {
                log.error("p2p-sync write conflict: phase=finalize, taskId={}, path={}, eventUid={}, msg={}",
                    taskId, relativePath, eventUid, ack.getMessage());
                acker.fail(ack.getMessage());
                return;
            }
            acker.retry();
        });
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
}
