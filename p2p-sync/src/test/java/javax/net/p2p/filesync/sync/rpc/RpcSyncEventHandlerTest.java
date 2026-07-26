package javax.net.p2p.filesync.sync.rpc;

import com.google.protobuf.Message;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.filesync.sync.FileSyncEventType;
import javax.net.p2p.filesync.sync.SyncUploadStatus;
import javax.net.p2p.interfaces.P2PFileService;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.rpc.api.RpcClient;
import javax.net.p2p.rpc.api.RpcUnaryResult;
import javax.net.p2p.rpc.model.RpcCallOptions;
import javax.net.p2p.rpc.server.SyncRpcServices;
import javax.net.p2p.rpc.sync.proto.SyncEventAck;
import javax.net.p2p.rpc.sync.proto.SyncEventRequest;
import javax.net.p2p.rpc.sync.proto.SyncFinalizeRequest;
import org.junit.Assert;
import org.junit.Test;

public class RpcSyncEventHandlerTest {

    @Test
    public void shouldRetryWhenRemoteContentCheckFails() throws Exception {
        Path localFile = Files.createTempFile("p2p_sync_rpc_", ".txt");
        writeUtf8(localFile, "hello sync");

        AtomicBoolean finalizeCalled = new AtomicBoolean(false);
        RpcClient rpcClient = new RpcClient() {
            @Override
            public <Req extends Message, Resp extends Message> Resp unary(String service, String method, Req request, Class<Resp> responseType, RpcCallOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <Req extends Message, Resp extends Message> RpcUnaryResult<Resp> unaryDetailed(String service, String method, Req request, Class<Resp> responseType, RpcCallOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <Req extends Message, Resp extends Message> CompletableFuture<Resp> unaryAsync(String service, String method, Req request, Class<Resp> responseType, RpcCallOptions options) {
                if (SyncRpcServices.APPLY_EVENT.equals(method)) {
                    SyncEventRequest syncReq = (SyncEventRequest) request;
                    return CompletableFuture.completedFuture((Resp) SyncEventAck.newBuilder()
                        .setOk(true)
                        .setNeedsUpload(true)
                        .setStoreId(7)
                        .setEventUid(syncReq.getEventUid())
                        .build());
                }
                if (SyncRpcServices.FINALIZE_EVENT.equals(method)) {
                    finalizeCalled.set(true);
                    SyncFinalizeRequest syncReq = (SyncFinalizeRequest) request;
                    return CompletableFuture.completedFuture((Resp) SyncEventAck.newBuilder()
                        .setOk(true)
                        .setEventUid(syncReq.getEventUid())
                        .build());
                }
                CompletableFuture<Resp> future = new CompletableFuture<>();
                future.completeExceptionally(new IllegalArgumentException("unexpected method: " + method));
                return future;
            }
        };

        FakeFileService fileService = new FakeFileService();
        RpcSyncEventHandler handler = new RpcSyncEventHandler(rpcClient, fileService, 11L);

        CountDownLatch retryLatch = new CountDownLatch(1);
        AtomicInteger ackCount = new AtomicInteger();
        AtomicInteger retryCount = new AtomicInteger();
        handler.handle(FileSyncEventType.CREATE, 1L, "a.txt", localFile, false, new javax.net.p2p.filesync.sync.FileSyncAcker() {
            @Override
            public void ack() {
                ackCount.incrementAndGet();
            }

            @Override
            public void retry() {
                retryCount.incrementAndGet();
                retryLatch.countDown();
            }
        });

        Assert.assertTrue(retryLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(0, ackCount.get());
        Assert.assertEquals(1, retryCount.get());
        Assert.assertFalse(finalizeCalled.get());
        Assert.assertEquals("a.txt", fileService.path);
        Assert.assertTrue(fileService.length > 0L);
        Assert.assertNotNull(fileService.md5);
        SyncUploadStatus failed = waitForRecentFailedStatus(handler, 5, TimeUnit.SECONDS);
        Assert.assertEquals("a.txt", failed.getPath());
        Assert.assertEquals("failed", failed.getPhase());
        Assert.assertTrue(failed.getMessage().contains("remote content check failed"));
        handler.close();
    }

    @Test
    public void shouldExposeActiveSegmentedUploadStatus() throws Exception {
        Path localFile = Files.createTempFile("p2p_sync_rpc_large_", ".bin");
        Files.write(localFile, new byte[P2PConfig.DATA_BLOCK_SIZE + 1024]);

        RpcClient rpcClient = new RpcClient() {
            @Override
            public <Req extends Message, Resp extends Message> Resp unary(String service, String method, Req request, Class<Resp> responseType, RpcCallOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <Req extends Message, Resp extends Message> RpcUnaryResult<Resp> unaryDetailed(String service, String method, Req request, Class<Resp> responseType, RpcCallOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <Req extends Message, Resp extends Message> CompletableFuture<Resp> unaryAsync(String service, String method, Req request, Class<Resp> responseType, RpcCallOptions options) {
                if (SyncRpcServices.APPLY_EVENT.equals(method)) {
                    SyncEventRequest syncReq = (SyncEventRequest) request;
                    return CompletableFuture.completedFuture((Resp) SyncEventAck.newBuilder()
                        .setOk(true)
                        .setNeedsUpload(true)
                        .setStoreId(9)
                        .setEventUid(syncReq.getEventUid())
                        .build());
                }
                if (SyncRpcServices.FINALIZE_EVENT.equals(method)) {
                    SyncFinalizeRequest syncReq = (SyncFinalizeRequest) request;
                    return CompletableFuture.completedFuture((Resp) SyncEventAck.newBuilder()
                        .setOk(true)
                        .setEventUid(syncReq.getEventUid())
                        .build());
                }
                CompletableFuture<Resp> future = new CompletableFuture<Resp>();
                future.completeExceptionally(new IllegalArgumentException("unexpected method: " + method));
                return future;
            }
        };

        FakeFileService fileService = new FakeFileService();
        fileService.simulateSegmentedUpload();
        RpcSyncEventHandler handler = new RpcSyncEventHandler(rpcClient, fileService, 12L);

        CountDownLatch ackLatch = new CountDownLatch(1);
        handler.handle(FileSyncEventType.MODIFY, 2L, "big.bin", localFile, false, new javax.net.p2p.filesync.sync.FileSyncAcker() {
            @Override
            public void ack() {
                ackLatch.countDown();
            }

            @Override
            public void retry() {
            }
        });

        Assert.assertTrue(fileService.uploadStarted.await(5, TimeUnit.SECONDS));
        SyncUploadStatus status = waitForUploadStatus(handler, 5, TimeUnit.SECONDS);
        Assert.assertEquals("big.bin", status.getPath());
        Assert.assertTrue(status.isSegmented());
        Assert.assertEquals(2, status.getTotalSegments());
        waitUntilUploadedSegments(handler, 1, 5, TimeUnit.SECONDS);

        fileService.releaseUpload.countDown();
        Assert.assertTrue(ackLatch.await(5, TimeUnit.SECONDS));
        waitUntilNoUploads(handler, 5, TimeUnit.SECONDS);
        Assert.assertTrue(handler.snapshotActiveUploads(10).isEmpty());
        SyncUploadStatus completed = waitForRecentCompletedStatus(handler, 5, TimeUnit.SECONDS);
        Assert.assertEquals("big.bin", completed.getPath());
        Assert.assertEquals("completed", completed.getPhase());
        handler.close();
    }

    private static final class FakeFileService implements P2PFileService {
        private String path;
        private long length;
        private String md5;
        private CountDownLatch uploadStarted;
        private CountDownLatch releaseUpload;
        private boolean segmentedUpload;
        private boolean checkWithMd5Result;

        private void simulateSegmentedUpload() {
            this.segmentedUpload = true;
            this.checkWithMd5Result = true;
            this.uploadStarted = new CountDownLatch(1);
            this.releaseUpload = new CountDownLatch(1);
        }

        @Override
        public FileDataModel getFileStream(int storeId, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileDataModel getFileData(int storeId, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileSegmentsDataModel getFileSegment(FileSegmentsDataModel segments) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void getFileData(int storeId, String path, File localFie) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void getFileData(int storeId, String path, Path localFie) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putFileData(int storeId, String path, byte[] data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putFileData(int storeId, String path, Path localfile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putFileData(int storeId, String path, File localfile) throws Exception {
            this.path = path;
            if (segmentedUpload) {
                File idx = new File(System.getProperty("java.io.tmpdir"), path + ".up.idx");
                if (idx.getParentFile() != null && !idx.getParentFile().exists()) {
                    idx.getParentFile().mkdirs();
                }
                Files.write(idx.toPath(), Collections.singletonList("0"), Charset.forName("UTF-8"));
                uploadStarted.countDown();
                releaseUpload.await(5, TimeUnit.SECONDS);
                idx.delete();
            }
        }

        @Override
        public void putFileSegment(FileSegmentsDataModel model) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void forcePutFileData(int storeId, String path, byte[] data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(int storeId, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean check(int storeId, String path, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean checkWithMd5(int storeId, String path, long length, String md5) {
            this.path = path;
            this.length = length;
            this.md5 = md5;
            return checkWithMd5Result;
        }

        @Override
        public FileDataModel infoFile(int storeId, String path, String md5) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(int storeId, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mkdirs(int storeId, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean rename(int storeId, String src, String dst) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> ls(int storeId, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String echo(String msg) {
            throw new UnsupportedOperationException();
        }
    }

    private static void writeUtf8(Path path, String value) throws Exception {
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static SyncUploadStatus waitForUploadStatus(RpcSyncEventHandler handler, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            List<SyncUploadStatus> statuses = handler.snapshotActiveUploads(10);
            if (!statuses.isEmpty()) {
                return statuses.get(0);
            }
            Thread.sleep(50L);
        }
        Assert.fail("upload status not exposed in time");
        return null;
    }

    private static void waitUntilNoUploads(RpcSyncEventHandler handler, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (handler.snapshotActiveUploads(10).isEmpty()) {
                return;
            }
            Thread.sleep(50L);
        }
        Assert.fail("upload status not cleared in time");
    }

    private static void waitUntilUploadedSegments(RpcSyncEventHandler handler, int expectedMin, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            List<SyncUploadStatus> statuses = handler.snapshotActiveUploads(10);
            if (!statuses.isEmpty() && statuses.get(0).getUploadedSegments() >= expectedMin) {
                return;
            }
            Thread.sleep(50L);
        }
        Assert.fail("upload progress not observed in time");
    }

    private static SyncUploadStatus waitForRecentCompletedStatus(RpcSyncEventHandler handler, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            List<SyncUploadStatus> statuses = handler.snapshotRecentCompletedUploads(10);
            if (!statuses.isEmpty()) {
                return statuses.get(0);
            }
            Thread.sleep(50L);
        }
        Assert.fail("recent completed upload history not exposed in time");
        return null;
    }

    private static SyncUploadStatus waitForRecentFailedStatus(RpcSyncEventHandler handler, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            List<SyncUploadStatus> statuses = handler.snapshotRecentFailedUploads(10);
            if (!statuses.isEmpty()) {
                return statuses.get(0);
            }
            Thread.sleep(50L);
        }
        Assert.fail("recent failed upload history not exposed in time");
        return null;
    }
}
