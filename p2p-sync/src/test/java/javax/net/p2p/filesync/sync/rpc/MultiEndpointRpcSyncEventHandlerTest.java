package javax.net.p2p.filesync.sync.rpc;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
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
            failingHandler(calls, "write_conflict"),
            retryingHandler(calls)
        ));

        AtomicInteger ackCount = new AtomicInteger();
        AtomicInteger retryCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        AtomicInteger reasonCount = new AtomicInteger();
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
                if ("write_conflict".equals(reason)) {
                    reasonCount.incrementAndGet();
                }
            }
        });

        Assert.assertEquals(3, calls.get());
        Assert.assertEquals(0, ackCount.get());
        Assert.assertEquals(0, retryCount.get());
        Assert.assertEquals(1, failCount.get());
        Assert.assertEquals(1, reasonCount.get());
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
