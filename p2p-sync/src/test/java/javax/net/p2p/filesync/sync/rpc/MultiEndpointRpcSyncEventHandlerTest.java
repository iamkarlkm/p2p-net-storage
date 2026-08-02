package javax.net.p2p.filesync.sync.rpc;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.p2p.filesync.sync.FileSyncAcker;
import javax.net.p2p.filesync.sync.FileSyncEventHandler;
import javax.net.p2p.filesync.sync.FileSyncEventType;
import javax.net.p2p.filesync.sync.SyncUploadStatus;
import javax.net.p2p.filesync.sync.SyncUploadStatusProvider;

import org.junit.Assert;
import org.junit.Test;

public class MultiEndpointRpcSyncEventHandlerTest {

    @Test
    public void shouldFanOutToAllEndpointsAndAckWhenAllAck() {
        AtomicInteger calls = new AtomicInteger();
        MultiEndpointRpcSyncEventHandler handler = MultiEndpointRpcSyncEventHandler.forHandlers(101L, Arrays.asList(
            ackingHandler(calls),
            ackingHandler(calls),
            ackingHandler(calls)
        ));

        AtomicInteger ackCount = new AtomicInteger();
        AtomicInteger retryCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        handler.handle(FileSyncEventType.CREATE, 1L, "a.txt", samplePath(), false, new FileSyncAcker() {
            @Override
            public void ack() {
                ackCount.incrementAndGet();
            }

            @Override
            public void retry() {
                retryCount.incrementAndGet();
            }

            @Override
            public void fail(String reason) {
                failCount.incrementAndGet();
            }
        });

        Assert.assertEquals(3, calls.get());
        Assert.assertEquals(1, ackCount.get());
        Assert.assertEquals(0, retryCount.get());
        Assert.assertEquals(0, failCount.get());
    }

    @Test
    public void shouldRetryWhenAnyEndpointRetries() {
        AtomicInteger calls = new AtomicInteger();
        MultiEndpointRpcSyncEventHandler handler = MultiEndpointRpcSyncEventHandler.forHandlers(101L, Arrays.asList(
            ackingHandler(calls),
            retryingHandler(calls),
            ackingHandler(calls)
        ));

        AtomicInteger ackCount = new AtomicInteger();
        AtomicInteger retryCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        handler.handle(FileSyncEventType.MODIFY, 1L, "a.txt", samplePath(), false, new FileSyncAcker() {
            @Override
            public void ack() {
                ackCount.incrementAndGet();
            }

            @Override
            public void retry() {
                retryCount.incrementAndGet();
            }

            @Override
            public void fail(String reason) {
                failCount.incrementAndGet();
            }
        });

        Assert.assertEquals(3, calls.get());
        Assert.assertEquals(0, ackCount.get());
        Assert.assertEquals(1, retryCount.get());
        Assert.assertEquals(0, failCount.get());
    }

    @Test
    public void shouldFailWhenAnyEndpointFails() {
        AtomicInteger calls = new AtomicInteger();
        MultiEndpointRpcSyncEventHandler handler = MultiEndpointRpcSyncEventHandler.forHandlers(101L, Arrays.asList(
            ackingHandler(calls),
            failingHandler(calls, "network_unreachable"),
            retryingHandler(calls)
        ));

        AtomicInteger ackCount = new AtomicInteger();
        AtomicInteger retryCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        AtomicReference<String> reasonRef = new AtomicReference<>("");
        handler.handle(FileSyncEventType.CREATE, 1L, "a.txt", samplePath(), false, new FileSyncAcker() {
            @Override
            public void ack() {
                ackCount.incrementAndGet();
            }

            @Override
            public void retry() {
                retryCount.incrementAndGet();
            }

            @Override
            public void fail(String reason) {
                failCount.incrementAndGet();
                reasonRef.set(reason == null ? "" : reason);
            }
        });

        Assert.assertEquals(3, calls.get());
        Assert.assertEquals(0, ackCount.get());
        Assert.assertEquals(0, retryCount.get());
        Assert.assertEquals(1, failCount.get());
        Assert.assertTrue(reasonRef.get(), reasonRef.get().contains("network_unreachable"));
        Assert.assertTrue(reasonRef.get(), reasonRef.get().contains("handler-2"));
    }

    @Test
    public void shouldOnlyReplayPendingReplicaAfterRetry() {
        AtomicInteger replica1Calls = new AtomicInteger();
        AtomicInteger replica2Calls = new AtomicInteger();
        AtomicBoolean replica2Recovered = new AtomicBoolean(false);
        MultiEndpointRpcSyncEventHandler handler = MultiEndpointRpcSyncEventHandler.forHandlers(101L, Arrays.asList(
            ackingHandler(replica1Calls),
            (type, fileId, relativePath, absolutePath, directory, acker) -> {
                replica2Calls.incrementAndGet();
                if (replica2Recovered.get()) {
                    acker.ack();
                    return;
                }
                acker.retry();
            }
        ));

        AtomicInteger ackCount = new AtomicInteger();
        AtomicInteger retryCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        FileSyncAcker aggregate = new FileSyncAcker() {
            @Override
            public void ack() {
                ackCount.incrementAndGet();
            }

            @Override
            public void retry() {
                retryCount.incrementAndGet();
            }

            @Override
            public void fail(String reason) {
                failCount.incrementAndGet();
            }
        };

        handler.handle(FileSyncEventType.MODIFY, 1L, "a.txt", samplePath(), false, aggregate);
        Assert.assertEquals(1, replica1Calls.get());
        Assert.assertEquals(1, replica2Calls.get());
        Assert.assertEquals(0, ackCount.get());
        Assert.assertEquals(1, retryCount.get());
        Assert.assertEquals(0, failCount.get());

        replica2Recovered.set(true);
        handler.handle(FileSyncEventType.MODIFY, 1L, "a.txt", samplePath(), false, aggregate);
        Assert.assertEquals(1, replica1Calls.get());
        Assert.assertEquals(2, replica2Calls.get());
        Assert.assertEquals(1, ackCount.get());
        Assert.assertEquals(1, retryCount.get());
        Assert.assertEquals(0, failCount.get());
    }

    @Test
    public void shouldAggregateUploadStatusesFromReplicaHandlers() {
        long now = System.currentTimeMillis();
        MultiEndpointRpcSyncEventHandler handler = MultiEndpointRpcSyncEventHandler.forHandlers(101L, Arrays.asList(
            new UploadStatusHandler(
                Collections.singletonList(new SyncUploadStatus(10L, 11L, "a.bin", "uploading", 32L, true, 4, 2, now - 2000L, now - 500L, now - 500L, 1, null, null)),
                Collections.singletonList(new SyncUploadStatus(20L, 21L, "done.bin", "completed", 16L, false, 1, 1, now - 4000L, now - 1000L, "")),
                Collections.singletonList(new SyncUploadStatus(30L, 31L, "fail.bin", "failed", 8L, false, 1, 0, now - 5000L, now - 1200L, "write_conflict"))
            ),
            new UploadStatusHandler(
                Collections.singletonList(new SyncUploadStatus(40L, 41L, "b.bin", "uploading", 64L, true, 8, 7, now - 1000L, now - 100L, now - 100L, 3, null, null)),
                Collections.singletonList(new SyncUploadStatus(50L, 51L, "done-2.bin", "completed", 20L, false, 1, 1, now - 6000L, now - 900L, "")),
                Collections.singletonList(new SyncUploadStatus(60L, 61L, "fail-2.bin", "failed", 12L, false, 1, 0, now - 7000L, now - 800L, "network_unreachable"))
            )
        ));

        List<SyncUploadStatus> active = handler.snapshotActiveUploads(10);
        Assert.assertEquals(2, active.size());
        Assert.assertEquals("b.bin", active.get(0).getPath());
        Assert.assertEquals("handler-2", active.get(0).getReplicaLabel());
        Assert.assertTrue(active.get(0).isResumedUpload());
        Assert.assertEquals(3, active.get(0).getResumedSegments());
        Assert.assertEquals("a.bin", active.get(1).getPath());
        Assert.assertEquals("handler-1", active.get(1).getReplicaLabel());
        Assert.assertTrue(active.get(1).isResumedUpload());
        Assert.assertEquals(1, active.get(1).getResumedSegments());

        List<SyncUploadStatus> completed = handler.snapshotRecentCompletedUploads(10);
        Assert.assertEquals(2, completed.size());
        Assert.assertEquals("done-2.bin", completed.get(0).getPath());
        Assert.assertEquals("handler-2", completed.get(0).getReplicaLabel());

        List<SyncUploadStatus> failed = handler.snapshotRecentFailedUploads(10);
        Assert.assertEquals(2, failed.size());
        Assert.assertEquals("fail-2.bin", failed.get(0).getPath());
        Assert.assertTrue(failed.get(0).getMessage().contains("network_unreachable"));
        Assert.assertEquals("handler-2", failed.get(0).getReplicaLabel());
    }

    private static FileSyncEventHandler ackingHandler(AtomicInteger calls) {
        return (type, fileId, relativePath, absolutePath, directory, acker) -> {
            calls.incrementAndGet();
            acker.ack();
        };
    }

    private static FileSyncEventHandler retryingHandler(AtomicInteger calls) {
        return (type, fileId, relativePath, absolutePath, directory, acker) -> {
            calls.incrementAndGet();
            acker.retry();
        };
    }

    private static FileSyncEventHandler failingHandler(AtomicInteger calls, String reason) {
        return (type, fileId, relativePath, absolutePath, directory, acker) -> {
            calls.incrementAndGet();
            acker.fail(reason);
        };
    }

    private static final class UploadStatusHandler implements FileSyncEventHandler, SyncUploadStatusProvider {
        private final List<SyncUploadStatus> active;
        private final List<SyncUploadStatus> completed;
        private final List<SyncUploadStatus> failed;

        private UploadStatusHandler(List<SyncUploadStatus> active, List<SyncUploadStatus> completed, List<SyncUploadStatus> failed) {
            this.active = active;
            this.completed = completed;
            this.failed = failed;
        }

        @Override
        public void handle(FileSyncEventType type, long fileId, String relativePath, Path absolutePath, boolean directory, FileSyncAcker acker) {
            acker.ack();
        }

        @Override
        public List<SyncUploadStatus> snapshotActiveUploads(int limit) {
            return active;
        }

        @Override
        public List<SyncUploadStatus> snapshotRecentCompletedUploads(int limit) {
            return completed;
        }

        @Override
        public List<SyncUploadStatus> snapshotRecentFailedUploads(int limit) {
            return failed;
        }
    }

    private static Path samplePath() {
        return Paths.get("a.txt");
    }
}
