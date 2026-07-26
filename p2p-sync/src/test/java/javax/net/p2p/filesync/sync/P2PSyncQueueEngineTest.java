package javax.net.p2p.filesync.sync;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class P2PSyncQueueEngineTest {

    @Test
    public void shouldTrackRetryCountAcrossRetryFailAndManualRetry() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_queue_retry_root_");
        Path state = Files.createTempDirectory("p2p_sync_queue_retry_state_");
        Files.write(root.resolve("a.txt"), "v1".getBytes(StandardCharsets.UTF_8));

        try (P2PSyncStateStore store = new P2PSyncStateStore(state)) {
            long fileId = store.getOrCreateFileId("a.txt");
            store.putKind(fileId, false);
            store.enqueueFileModify(fileId);

            AtomicInteger calls = new AtomicInteger();
            FileSyncEventHandler handler = (type, id, rel, abs, dir, acker) -> {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    acker.retry();
                    return;
                }
                if (call == 2) {
                    acker.fail("network");
                    return;
                }
                acker.ack();
            };

            P2PSyncQueueEngine engine = new P2PSyncQueueEngine();
            AtomicBoolean running = new AtomicBoolean(true);

            Assert.assertEquals(1, engine.processBatch(store, P2PSyncStateStore.QueueStage.ACTIVE, 10, root, handler, running));
            Assert.assertEquals(1, store.getRetryCount(FileSyncEventType.MODIFY, false, fileId));
            Assert.assertTrue(store.fileModifiesActive().contains(Long.valueOf(fileId)));

            Assert.assertEquals(1, engine.processBatch(store, P2PSyncStateStore.QueueStage.ACTIVE, 10, root, handler, running));
            Assert.assertTrue(store.fileModifiesFailed().contains(Long.valueOf(fileId)));
            Assert.assertEquals("network", store.getFailedReason(FileSyncEventType.MODIFY, false, fileId));
            Assert.assertEquals(1, store.getRetryCount(FileSyncEventType.MODIFY, false, fileId));

            Assert.assertTrue(store.retryFailed(FileSyncEventType.MODIFY, false, fileId));
            Assert.assertEquals(2, store.getRetryCount(FileSyncEventType.MODIFY, false, fileId));
            Assert.assertTrue(store.fileModifiesActive().contains(Long.valueOf(fileId)));

            Assert.assertEquals(1, engine.processBatch(store, P2PSyncStateStore.QueueStage.ACTIVE, 10, root, handler, running));
            Assert.assertEquals(0, store.getRetryCount(FileSyncEventType.MODIFY, false, fileId));
            Assert.assertFalse(store.fileModifiesActive().contains(Long.valueOf(fileId)));
            Assert.assertFalse(store.fileModifiesFailed().contains(Long.valueOf(fileId)));
        }
    }
}
