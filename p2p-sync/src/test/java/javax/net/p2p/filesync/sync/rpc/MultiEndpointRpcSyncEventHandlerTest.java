package javax.net.p2p.filesync.sync.rpc;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.p2p.filesync.sync.FileSyncAcker;
import javax.net.p2p.filesync.sync.FileSyncEventHandler;
import javax.net.p2p.filesync.sync.FileSyncEventType;
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

    private static Path samplePath() {
        return Paths.get("a.txt");
    }
}
