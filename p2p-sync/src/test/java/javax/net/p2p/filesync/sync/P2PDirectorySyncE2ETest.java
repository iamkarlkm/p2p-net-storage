package javax.net.p2p.filesync.sync;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import javax.net.p2p.client.P2PClientTcp;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.filesync.config.P2PSyncConfig;
import javax.net.p2p.filesync.monitor.P2PSyncMonitorServer;
import javax.net.p2p.filesync.sync.rpc.MultiEndpointRpcSyncEventHandler;
import javax.net.p2p.filesync.sync.rpc.RpcSyncEventHandler;
import javax.net.p2p.filesync.sync.rpc.server.SyncApplyEventRpcRegistration;
import javax.net.p2p.rpc.client.P2PRpcClient;
import javax.net.p2p.server.P2PServerTcp;
import javax.net.p2p.utils.P2PUtils;

import org.junit.Assert;
import org.junit.Test;

public class P2PDirectorySyncE2ETest {

    @Test
    public void shouldSyncFileToReceiverOverTcp() throws Exception {
        long taskId = 101L;
        Path senderRoot = Files.createTempDirectory("p2p_sync_sender_root_");
        Path senderState = Files.createTempDirectory("p2p_sync_sender_state_");
        try (ReceiverNode receiver = ReceiverNode.start(taskId, 501);
             ManagedTcpHandler handler = ManagedTcpHandler.connect(taskId, receiver.port)) {
            try (P2PDirectorySyncService svc = new P2PDirectorySyncService(senderConfig(taskId, senderRoot, senderState), handler)) {
                svc.start();
                waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

                Path senderFile = senderRoot.resolve("nested").resolve("hello.txt");
                Files.createDirectories(senderFile.getParent());
                long ts = System.currentTimeMillis() - 4_000L;
                writeUtf8(senderFile, "hello sync");
                Files.setLastModifiedTime(senderFile, FileTime.fromMillis(ts));

                assertFileSynced(receiver.root.resolve("nested").resolve("hello.txt"), "hello sync", ts);
            }
        }
    }

    @Test
    public void shouldTreatFileRenameAsDeletePlusCreateOverTcp() throws Exception {
        long taskId = 1011L;
        Path senderRoot = Files.createTempDirectory("p2p_sync_sender_root_rename_file_");
        Path senderState = Files.createTempDirectory("p2p_sync_sender_state_rename_file_");
        try (ReceiverNode receiver = ReceiverNode.start(taskId, 1501);
             ManagedTcpHandler handler = ManagedTcpHandler.connect(taskId, receiver.port);
             P2PDirectorySyncService svc = new P2PDirectorySyncService(senderConfig(taskId, senderRoot, senderState), handler)) {
            svc.start();
            waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

            Path oldFile = senderRoot.resolve("rename").resolve("old.txt");
            Files.createDirectories(oldFile.getParent());
            long initialTs = System.currentTimeMillis() - 4_000L;
            writeUtf8(oldFile, "rename payload");
            Files.setLastModifiedTime(oldFile, FileTime.fromMillis(initialTs));
            assertFileSynced(receiver.root.resolve("rename").resolve("old.txt"), "rename payload", initialTs);

            Path newFile = senderRoot.resolve("rename").resolve("new.txt");
            long renamedTs = System.currentTimeMillis() - 2_000L;
            Files.move(oldFile, newFile);
            Files.setLastModifiedTime(newFile, FileTime.fromMillis(renamedTs));

            assertPathAbsent(receiver.root.resolve("rename").resolve("old.txt"));
            assertFileSynced(receiver.root.resolve("rename").resolve("new.txt"), "rename payload", renamedTs);
        }
    }

    @Test
    public void shouldSyncMovedInDirectoryTreeOverTcp() throws Exception {
        long taskId = 1012L;
        Path senderRoot = Files.createTempDirectory("p2p_sync_sender_root_move_dir_");
        Path senderState = Files.createTempDirectory("p2p_sync_sender_state_move_dir_");
        try (ReceiverNode receiver = ReceiverNode.start(taskId, 1502);
             ManagedTcpHandler handler = ManagedTcpHandler.connect(taskId, receiver.port);
             P2PDirectorySyncService svc = new P2PDirectorySyncService(senderConfig(taskId, senderRoot, senderState), handler)) {
            svc.start();
            waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

            Path externalRoot = Files.createTempDirectory("p2p_sync_sender_external_tree_");
            Path sourceDir = externalRoot.resolve("src");
            Path sourceFile = sourceDir.resolve("nested").resolve("hello.txt");
            Files.createDirectories(sourceFile.getParent());
            long initialTs = System.currentTimeMillis() - 4_000L;
            writeUtf8(sourceFile, "move directory payload");
            Files.setLastModifiedTime(sourceFile, FileTime.fromMillis(initialTs));

            Path targetDir = senderRoot.resolve("archive").resolve("dst");
            Files.createDirectories(targetDir.getParent());
            Files.move(sourceDir, targetDir);

            assertFileSynced(receiver.root.resolve("archive").resolve("dst").resolve("nested").resolve("hello.txt"),
                "move directory payload", initialTs);
        }
    }

    @Test
    public void shouldFanOutFileToMultipleReceiversOverTcp() throws Exception {
        long taskId = 102L;
        Path senderRoot = Files.createTempDirectory("p2p_sync_sender_root_fanout_");
        Path senderState = Files.createTempDirectory("p2p_sync_sender_state_fanout_");
        try (ReceiverNode receiver1 = ReceiverNode.start(taskId, 511);
             ReceiverNode receiver2 = ReceiverNode.start(taskId, 512);
             ManagedTcpHandler handler1 = ManagedTcpHandler.connect(taskId, receiver1.port);
             ManagedTcpHandler handler2 = ManagedTcpHandler.connect(taskId, receiver2.port);
             MultiEndpointRpcSyncEventHandler fanOut = MultiEndpointRpcSyncEventHandler.forHandlers(taskId, Arrays.asList(handler1, handler2));
             P2PDirectorySyncService svc = new P2PDirectorySyncService(senderConfig(taskId, senderRoot, senderState), fanOut)) {
            svc.start();
            waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

            Path senderFile = senderRoot.resolve("fanout").resolve("hello.txt");
            Files.createDirectories(senderFile.getParent());
            long ts = System.currentTimeMillis() - 5_000L;
            writeUtf8(senderFile, "fanout sync");
            Files.setLastModifiedTime(senderFile, FileTime.fromMillis(ts));

            assertFileSynced(receiver1.root.resolve("fanout").resolve("hello.txt"), "fanout sync", ts);
            assertFileSynced(receiver2.root.resolve("fanout").resolve("hello.txt"), "fanout sync", ts);
        }
    }

    @Test
    public void shouldSyncLargeFileWithSegmentationOverTcp() throws Exception {
        long taskId = 108L;
        int originalBlockSize = P2PConfig.DATA_PUT_BLOCK_SIZE;
        P2PConfig.DATA_PUT_BLOCK_SIZE = 8 * 1024;
        Path senderRoot = Files.createTempDirectory("p2p_sync_sender_root_large_");
        Path senderState = Files.createTempDirectory("p2p_sync_sender_state_large_");
        try (ReceiverNode receiver = ReceiverNode.start(taskId, 701);
             ManagedTcpHandler handler = ManagedTcpHandler.connect(taskId, receiver.port);
             P2PDirectorySyncService svc = new P2PDirectorySyncService(senderConfig(taskId, senderRoot, senderState), handler);
             P2PSyncMonitorServer monitor = new P2PSyncMonitorServer(svc, new InetSocketAddress("127.0.0.1", 0))) {
            svc.start();
            monitor.start();
            waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

            Path senderFile = senderRoot.resolve("large").resolve("big.bin");
            Files.createDirectories(senderFile.getParent());
            int size = P2PConfig.DATA_PUT_BLOCK_SIZE * 6 + 123;
            byte[] payload = new byte[size];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i % 251);
            }
            Files.write(senderFile, payload);
            long ts = System.currentTimeMillis() - 4_000L;
            Files.setLastModifiedTime(senderFile, FileTime.fromMillis(ts));

            assertFileBytesSynced(receiver.root.resolve("large").resolve("big.bin"), payload, ts);
            waitForQueuesJsonContains(monitor.getPort(),
                "\"recentCompletedUploads\"",
                "\"path\":\"large/big.bin\"",
                "\"segmented\":true");
        } finally {
            P2PConfig.DATA_PUT_BLOCK_SIZE = originalBlockSize;
        }
    }

    @Test
    public void shouldRecordFailingReplicaWhenOnlySubsetOfReplicasSync() throws Exception {
        long taskId = 103L;
        Path senderRoot = Files.createTempDirectory("p2p_sync_sender_root_partial_");
        Path senderState = Files.createTempDirectory("p2p_sync_sender_state_partial_");

        try (ReceiverNode receiver = ReceiverNode.start(taskId, 521);
             ManagedTcpHandler liveHandler = ManagedTcpHandler.connect(taskId, receiver.port);
             MultiEndpointRpcSyncEventHandler fanOut = MultiEndpointRpcSyncEventHandler.forHandlers(taskId, java.util.Arrays.asList(
                 liveHandler,
                 failingHandler("network_unreachable")));
             P2PDirectorySyncService svc = new P2PDirectorySyncService(senderConfig(taskId, senderRoot, senderState), fanOut)) {
            svc.start();
            waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

            Path senderFile = senderRoot.resolve("partial.txt");
            writeUtf8(senderFile, "partial sync");

            Path receiverFile = receiver.root.resolve("partial.txt");
            waitUntil(() -> Files.isRegularFile(receiverFile), 10, TimeUnit.SECONDS);
            waitUntil(() -> "partial sync".equals(readUtf8(receiverFile)), 10, TimeUnit.SECONDS);

            long fileId = svc.getStore().getOrCreateFileId("partial.txt");
            waitUntil(() -> svc.getStore().fileCreatesFailed().contains(Long.valueOf(fileId)), 10, TimeUnit.SECONDS);
            waitUntil(() -> {
                String current = svc.getStore().getFailedReason(FileSyncEventType.CREATE, false, fileId);
                return current != null && current.contains("network_unreachable");
            }, 10, TimeUnit.SECONDS);

            String reason = svc.getStore().getFailedReason(FileSyncEventType.CREATE, false, fileId);
            Assert.assertTrue(reason, reason.contains("network_unreachable"));
            Assert.assertTrue(reason, reason.contains("handler-2"));
        }
    }

    @Test
    public void shouldReplayOnlyFailedReplicaAfterManualRetry() throws Exception {
        long taskId = 104L;
        Path senderRoot = Files.createTempDirectory("p2p_sync_sender_root_recover_");
        Path senderState = Files.createTempDirectory("p2p_sync_sender_state_recover_");
        AtomicInteger replica1Calls = new AtomicInteger();
        AtomicInteger replica2Calls = new AtomicInteger();
        RecoverableHandler recoverable = new RecoverableHandler();
        ReceiverNode receiver3 = null;
        CountingHandler counted3 = null;
        try (ReceiverNode receiver1 = ReceiverNode.start(taskId, 531);
             ReceiverNode receiver2 = ReceiverNode.start(taskId, 532);
             ManagedTcpHandler handler1 = ManagedTcpHandler.connect(taskId, receiver1.port);
             ManagedTcpHandler handler2 = ManagedTcpHandler.connect(taskId, receiver2.port);
             CountingHandler counted1 = new CountingHandler(handler1, replica1Calls);
             CountingHandler counted2 = new CountingHandler(handler2, replica2Calls);
             MultiEndpointRpcSyncEventHandler fanOut = MultiEndpointRpcSyncEventHandler.forHandlers(
                 taskId,
                 Arrays.asList(counted1, counted2, recoverable));
             P2PDirectorySyncService svc = new P2PDirectorySyncService(senderConfig(taskId, senderRoot, senderState), fanOut)) {
            svc.start();
            waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

            Path senderFile = senderRoot.resolve("recover").resolve("hello.txt");
            Files.createDirectories(senderFile.getParent());
            writeUtf8(senderFile, "recover sync");

            assertFileSynced(receiver1.root.resolve("recover").resolve("hello.txt"), "recover sync",
                Files.getLastModifiedTime(senderFile).toMillis());
            assertFileSynced(receiver2.root.resolve("recover").resolve("hello.txt"), "recover sync",
                Files.getLastModifiedTime(senderFile).toMillis());

            long fileId = svc.getStore().getOrCreateFileId("recover/hello.txt");
            waitUntil(() -> svc.getStore().fileCreatesFailed().contains(Long.valueOf(fileId)), 10, TimeUnit.SECONDS);
            int replica1Baseline = replica1Calls.get();
            int replica2Baseline = replica2Calls.get();
            int recoverableBaseline = recoverable.getAttemptCount();
            Assert.assertTrue(replica1Calls.get() > 0);
            Assert.assertTrue(replica2Calls.get() > 0);
            Assert.assertTrue(recoverable.getAttemptCount() > 0);

            receiver3 = ReceiverNode.start(taskId, 533);
            counted3 = new CountingHandler(ManagedTcpHandler.connect(taskId, receiver3.port), new AtomicInteger());
            recoverable.recover(counted3);

            Assert.assertTrue(svc.getStore().retryFailed(FileSyncEventType.CREATE, false, fileId));
            Path receiver3File = receiver3.root.resolve("recover").resolve("hello.txt");
            waitUntil(() -> Files.isRegularFile(receiver3File), 10, TimeUnit.SECONDS);
            waitUntil(() -> "recover sync".equals(readUtf8(receiver3File)), 10, TimeUnit.SECONDS);
            waitUntil(() -> !svc.getStore().fileCreatesFailed().contains(Long.valueOf(fileId)), 10, TimeUnit.SECONDS);

            Assert.assertEquals(replica1Baseline, replica1Calls.get());
            Assert.assertEquals(replica2Baseline, replica2Calls.get());
            Assert.assertTrue(recoverable.getAttemptCount() >= recoverableBaseline + 1);
            Assert.assertTrue(counted3.calls.get() >= 1);
        } finally {
            if (counted3 != null) {
                counted3.close();
            }
            if (receiver3 != null) {
                receiver3.close();
            }
        }
    }

    @Test
    public void shouldReplayOnlyFailedReplicaAfterRestartAndManualRetry() throws Exception {
        long taskId = 105L;
        Path senderRoot = Files.createTempDirectory("p2p_sync_sender_root_restart_");
        Path senderState = Files.createTempDirectory("p2p_sync_sender_state_restart_");
        AtomicInteger replica1Calls = new AtomicInteger();
        AtomicInteger replica2Calls = new AtomicInteger();
        RecoverableHandler recoverable = new RecoverableHandler();
        ReceiverNode receiver3 = null;
        CountingHandler counted3 = null;
        try (ReceiverNode receiver1 = ReceiverNode.start(taskId, 541);
             ReceiverNode receiver2 = ReceiverNode.start(taskId, 542);
             ManagedTcpHandler handler1 = ManagedTcpHandler.connect(taskId, receiver1.port);
             ManagedTcpHandler handler2 = ManagedTcpHandler.connect(taskId, receiver2.port);
             CountingHandler counted1 = new CountingHandler(handler1, replica1Calls);
             CountingHandler counted2 = new CountingHandler(handler2, replica2Calls)) {
            Path senderFile = senderRoot.resolve("restart").resolve("hello.txt");

            try (MultiEndpointRpcSyncEventHandler fanOut = MultiEndpointRpcSyncEventHandler.forHandlers(
                taskId,
                Arrays.asList(counted1, counted2, recoverable));
                 P2PDirectorySyncService svc = new P2PDirectorySyncService(senderConfig(taskId, senderRoot, senderState), fanOut)) {
                svc.start();
                waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

                Files.createDirectories(senderFile.getParent());
                writeUtf8(senderFile, "restart sync");

                assertFileSynced(receiver1.root.resolve("restart").resolve("hello.txt"), "restart sync",
                    Files.getLastModifiedTime(senderFile).toMillis());
                assertFileSynced(receiver2.root.resolve("restart").resolve("hello.txt"), "restart sync",
                    Files.getLastModifiedTime(senderFile).toMillis());

                long fileId = svc.getStore().getOrCreateFileId("restart/hello.txt");
                waitUntil(() -> svc.getStore().fileCreatesFailed().contains(Long.valueOf(fileId)), 10, TimeUnit.SECONDS);
            }

            Assert.assertTrue(replica1Calls.get() > 0);
            Assert.assertTrue(replica2Calls.get() > 0);
            Assert.assertTrue(recoverable.getAttemptCount() > 0);

            receiver3 = ReceiverNode.start(taskId, 543);
            counted3 = new CountingHandler(ManagedTcpHandler.connect(taskId, receiver3.port), new AtomicInteger());
            recoverable.recover(counted3);

            try (MultiEndpointRpcSyncEventHandler fanOut = MultiEndpointRpcSyncEventHandler.forHandlers(
                taskId,
                Arrays.asList(counted1, counted2, recoverable));
                 P2PDirectorySyncService restarted = new P2PDirectorySyncService(senderConfig(taskId, senderRoot, senderState), fanOut)) {
                restarted.start();
                waitUntil(() -> restarted.isWatchReady(), 5, TimeUnit.SECONDS);

                long fileId = restarted.getStore().getOrCreateFileId("restart/hello.txt");
                Assert.assertTrue(restarted.getStore().fileCreatesFailed().contains(Long.valueOf(fileId)));
                waitForStableValue(replica1Calls::get, 500L, 5, TimeUnit.SECONDS);
                waitForStableValue(replica2Calls::get, 500L, 5, TimeUnit.SECONDS);
                Assert.assertTrue(restarted.getStore().retryFailed(FileSyncEventType.CREATE, false, fileId));

                Path receiver3File = receiver3.root.resolve("restart").resolve("hello.txt");
                waitUntil(() -> Files.isRegularFile(receiver3File), 10, TimeUnit.SECONDS);
                waitUntil(() -> "restart sync".equals(readUtf8(receiver3File)), 10, TimeUnit.SECONDS);
                waitUntil(() -> !restarted.getStore().fileCreatesFailed().contains(Long.valueOf(fileId)), 10, TimeUnit.SECONDS);

                Assert.assertEquals(1, counted3.calls.get());
            }
        } finally {
            if (counted3 != null) {
                counted3.close();
            }
            if (receiver3 != null) {
                receiver3.close();
            }
        }
    }

    @Test
    public void shouldRetryOnlyTargetReplicaWhileKeepingOtherFailedReplicaUntouched() throws Exception {
        long taskId = 106L;
        Path senderRoot = Files.createTempDirectory("p2p_sync_sender_root_targeted_");
        Path senderState = Files.createTempDirectory("p2p_sync_sender_state_targeted_");
        AtomicInteger replica1Calls = new AtomicInteger();
        AtomicInteger replica2Calls = new AtomicInteger();
        RecoverableHandler recoverable3 = new RecoverableHandler();
        RecoverableHandler recoverable4 = new RecoverableHandler();
        ReceiverNode receiver3 = null;
        CountingHandler counted3 = null;
        try (ReceiverNode receiver1 = ReceiverNode.start(taskId, 551);
             ReceiverNode receiver2 = ReceiverNode.start(taskId, 552);
             ManagedTcpHandler handler1 = ManagedTcpHandler.connect(taskId, receiver1.port);
             ManagedTcpHandler handler2 = ManagedTcpHandler.connect(taskId, receiver2.port);
             CountingHandler counted1 = new CountingHandler(handler1, replica1Calls);
             CountingHandler counted2 = new CountingHandler(handler2, replica2Calls);
             MultiEndpointRpcSyncEventHandler fanOut = MultiEndpointRpcSyncEventHandler.forHandlers(
                 taskId,
                 Arrays.asList(counted1, counted2, recoverable3, recoverable4));
             P2PDirectorySyncService svc = new P2PDirectorySyncService(senderConfig(taskId, senderRoot, senderState), fanOut)) {
            svc.start();
            waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

            Path senderFile = senderRoot.resolve("targeted").resolve("hello.txt");
            Files.createDirectories(senderFile.getParent());
            writeUtf8(senderFile, "targeted sync");

            assertFileSynced(receiver1.root.resolve("targeted").resolve("hello.txt"), "targeted sync",
                Files.getLastModifiedTime(senderFile).toMillis());
            assertFileSynced(receiver2.root.resolve("targeted").resolve("hello.txt"), "targeted sync",
                Files.getLastModifiedTime(senderFile).toMillis());

            long fileId = svc.getStore().getOrCreateFileId("targeted/hello.txt");
            waitUntil(() -> svc.getStore().fileCreatesFailed().contains(Long.valueOf(fileId)), 10, TimeUnit.SECONDS);
            waitUntil(() -> hasReplicaState(svc.getStore(), FileSyncEventType.CREATE, false, fileId, "handler-3", P2PSyncStateStore.REPLICA_FAILED), 10, TimeUnit.SECONDS);
            waitUntil(() -> hasReplicaState(svc.getStore(), FileSyncEventType.CREATE, false, fileId, "handler-4", P2PSyncStateStore.REPLICA_FAILED), 10, TimeUnit.SECONDS);

            receiver3 = ReceiverNode.start(taskId, 553);
            counted3 = new CountingHandler(ManagedTcpHandler.connect(taskId, receiver3.port), new AtomicInteger());
            recoverable3.recover(counted3);

            int replica1Baseline = replica1Calls.get();
            int replica2Baseline = replica2Calls.get();
            int recoverable3Baseline = recoverable3.getAttemptCount();
            int recoverable4Baseline = recoverable4.getAttemptCount();
            Assert.assertTrue(svc.getStore().retryFailedReplica(FileSyncEventType.CREATE, false, fileId, "handler-3"));

            Path receiver3File = receiver3.root.resolve("targeted").resolve("hello.txt");
            waitUntil(() -> Files.isRegularFile(receiver3File), 10, TimeUnit.SECONDS);
            waitUntil(() -> "targeted sync".equals(readUtf8(receiver3File)), 10, TimeUnit.SECONDS);
            waitUntil(() -> svc.getStore().fileCreatesFailed().contains(Long.valueOf(fileId)), 10, TimeUnit.SECONDS);
            waitUntil(() -> hasReplicaState(svc.getStore(), FileSyncEventType.CREATE, false, fileId, "handler-3", P2PSyncStateStore.REPLICA_ACKED), 10, TimeUnit.SECONDS);
            waitUntil(() -> hasReplicaState(svc.getStore(), FileSyncEventType.CREATE, false, fileId, "handler-4", P2PSyncStateStore.REPLICA_FAILED), 10, TimeUnit.SECONDS);

            String reason = svc.getStore().getFailedReason(FileSyncEventType.CREATE, false, fileId);
            Assert.assertTrue(reason, reason.contains("handler-4"));
            Assert.assertEquals(replica1Baseline, replica1Calls.get());
            Assert.assertEquals(replica2Baseline, replica2Calls.get());
            Assert.assertEquals(recoverable3Baseline + 1, recoverable3.getAttemptCount());
            Assert.assertEquals(1, counted3.calls.get());
        } finally {
            if (counted3 != null) {
                counted3.close();
            }
            if (receiver3 != null) {
                receiver3.close();
            }
        }
    }

    @Test
    public void shouldReplayNetworkReplicaViaMonitorCategoryActionOverTcp() throws Exception {
        long taskId = 107L;
        Path senderRoot = Files.createTempDirectory("p2p_sync_sender_root_network_category_");
        Path senderState = Files.createTempDirectory("p2p_sync_sender_state_network_category_");
        AtomicInteger replica1Calls = new AtomicInteger();
        AtomicInteger replica2Calls = new AtomicInteger();
        RecoverableHandler recoverable = new RecoverableHandler();
        ReceiverNode receiver3 = null;
        CountingHandler counted3 = null;
        try (ReceiverNode receiver1 = ReceiverNode.start(taskId, 561);
             ReceiverNode receiver2 = ReceiverNode.start(taskId, 562);
             ManagedTcpHandler handler1 = ManagedTcpHandler.connect(taskId, receiver1.port);
             ManagedTcpHandler handler2 = ManagedTcpHandler.connect(taskId, receiver2.port);
             CountingHandler counted1 = new CountingHandler(handler1, replica1Calls);
             CountingHandler counted2 = new CountingHandler(handler2, replica2Calls);
             MultiEndpointRpcSyncEventHandler fanOut = MultiEndpointRpcSyncEventHandler.forHandlers(
                 taskId,
                 Arrays.asList(counted1, counted2, recoverable));
             P2PDirectorySyncService svc = new P2PDirectorySyncService(senderConfig(taskId, senderRoot, senderState), fanOut);
             P2PSyncMonitorServer monitor = new P2PSyncMonitorServer(svc, new InetSocketAddress("127.0.0.1", 0))) {
            svc.start();
            monitor.start();
            waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

            Path senderFile = senderRoot.resolve("network").resolve("hello.txt");
            Files.createDirectories(senderFile.getParent());
            writeUtf8(senderFile, "network category sync");

            assertFileSynced(receiver1.root.resolve("network").resolve("hello.txt"), "network category sync",
                Files.getLastModifiedTime(senderFile).toMillis());
            assertFileSynced(receiver2.root.resolve("network").resolve("hello.txt"), "network category sync",
                Files.getLastModifiedTime(senderFile).toMillis());

            long fileId = svc.getStore().getOrCreateFileId("network/hello.txt");
            waitUntil(() -> svc.getStore().fileCreatesFailed().contains(Long.valueOf(fileId)), 10, TimeUnit.SECONDS);
            waitUntil(() -> hasReplicaState(svc.getStore(), FileSyncEventType.CREATE, false, fileId, "handler-3", P2PSyncStateStore.REPLICA_FAILED), 10, TimeUnit.SECONDS);
            waitUntil(() -> {
                String current = svc.getStore().getFailedReason(FileSyncEventType.CREATE, false, fileId);
                return current != null && current.contains("network_unreachable");
            }, 10, TimeUnit.SECONDS);

            String queuesJson = sendHttp("GET", "http://127.0.0.1:" + monitor.getPort() + "/sync/api/queues?limit=20", null);
            Assert.assertTrue(queuesJson.contains("\"replicaFailureCategorySummary\""));
            assertContainsInOrder(queuesJson, "\"reason\":\"NETWORK\"", "\"recommendedAction\":\"RETRY_NETWORK_REPLICAS\"");
            Assert.assertTrue(queuesJson.contains("\"replicaCategorySummary\":\"NETWORK=1\""));

            receiver3 = ReceiverNode.start(taskId, 563);
            counted3 = new CountingHandler(ManagedTcpHandler.connect(taskId, receiver3.port), new AtomicInteger());
            recoverable.recover(counted3);

            int replica1Baseline = replica1Calls.get();
            int replica2Baseline = replica2Calls.get();
            int recoverableBaseline = recoverable.getAttemptCount();

            String retryResp = sendHttp(
                "POST",
                "http://127.0.0.1:" + monitor.getPort() + "/sync/api/failed/retry-replicas-by-category?category=NETWORK",
                "");
            Assert.assertTrue(retryResp.contains("\"ok\":true"));
            Assert.assertTrue(retryResp.contains("\"categories\":[\"NETWORK\"]"));
            assertJsonNumericFieldPositive(retryResp, "retriedReplicaCount");
            assertJsonFieldPresent(retryResp, "clearedFailedItemCount");
            assertJsonFieldPresent(retryResp, "clearedOutstandingReplicaCount");
            assertJsonFieldPresent(retryResp, "clearedReplicaCategorySummary");
            assertJsonFieldPresent(retryResp, "remainingFailedItemCount");
            assertJsonFieldPresent(retryResp, "remainingOutstandingReplicaCount");
            assertJsonFieldPresent(retryResp, "remainingReplicaCategorySummary");

            Path receiver3File = receiver3.root.resolve("network").resolve("hello.txt");
            waitUntil(() -> Files.isRegularFile(receiver3File), 10, TimeUnit.SECONDS);
            waitUntil(() -> "network category sync".equals(readUtf8(receiver3File)), 10, TimeUnit.SECONDS);
            waitUntil(() -> !svc.getStore().fileCreatesFailed().contains(Long.valueOf(fileId)), 10, TimeUnit.SECONDS);
            waitForQueuesJsonContains(monitor.getPort(),
                "\"action\":\"RETRY_REPLICAS_BY_CATEGORY\"",
                "\"phase\":\"operator_action\"",
                "\"clearedReplicaCategorySummary\":\"NETWORK=",
                "\"remainingOutstandingReplicaCount\":0",
                "\"remainingReplicaCategorySummary\":\"\"");

            Assert.assertEquals(replica1Baseline, replica1Calls.get());
            Assert.assertEquals(replica2Baseline, replica2Calls.get());
            Assert.assertTrue(recoverable.getAttemptCount() >= recoverableBaseline + 1);
            Assert.assertTrue(counted3.calls.get() >= 1);
        } finally {
            if (counted3 != null) {
                counted3.close();
            }
            if (receiver3 != null) {
                receiver3.close();
            }
        }
    }

    @Test
    public void shouldDiscardConflictReplicaViaMonitorCategoryActionOverTcp() throws Exception {
        long taskId = 108L;
        Path senderRoot = Files.createTempDirectory("p2p_sync_sender_root_conflict_category_");
        Path senderState = Files.createTempDirectory("p2p_sync_sender_state_conflict_category_");
        AtomicInteger replica1Calls = new AtomicInteger();
        AtomicInteger replica2Calls = new AtomicInteger();
        try (ReceiverNode receiver1 = ReceiverNode.start(taskId, 571);
             ReceiverNode receiver2 = ReceiverNode.start(taskId, 572);
             ManagedTcpHandler handler1 = ManagedTcpHandler.connect(taskId, receiver1.port);
             ManagedTcpHandler handler2 = ManagedTcpHandler.connect(taskId, receiver2.port);
             CountingHandler counted1 = new CountingHandler(handler1, replica1Calls);
             CountingHandler counted2 = new CountingHandler(handler2, replica2Calls);
             MultiEndpointRpcSyncEventHandler fanOut = MultiEndpointRpcSyncEventHandler.forHandlers(
                 taskId,
                 Arrays.asList(counted1, counted2, failingHandler("write_conflict")));
             P2PDirectorySyncService svc = new P2PDirectorySyncService(senderConfig(taskId, senderRoot, senderState), fanOut);
             P2PSyncMonitorServer monitor = new P2PSyncMonitorServer(svc, new InetSocketAddress("127.0.0.1", 0))) {
            svc.start();
            monitor.start();
            waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

            Path senderFile = senderRoot.resolve("conflict-hello.txt");
            writeUtf8(senderFile, "conflict category sync");

            assertFileSynced(receiver1.root.resolve("conflict-hello.txt"), "conflict category sync",
                Files.getLastModifiedTime(senderFile).toMillis());
            assertFileSynced(receiver2.root.resolve("conflict-hello.txt"), "conflict category sync",
                Files.getLastModifiedTime(senderFile).toMillis());

            long fileId = svc.getStore().getOrCreateFileId("conflict-hello.txt");
            waitUntil(() -> svc.getStore().fileCreatesFailed().contains(Long.valueOf(fileId)), 10, TimeUnit.SECONDS);
            waitUntil(() -> {
                String current = svc.getStore().getFailedReason(FileSyncEventType.CREATE, false, fileId);
                return current != null && current.contains("write_conflict");
            }, 10, TimeUnit.SECONDS);

            String queuesJson = sendHttp("GET", "http://127.0.0.1:" + monitor.getPort() + "/sync/api/queues?limit=20", null);
            assertContainsInOrder(queuesJson, "\"reason\":\"CONFLICT\"", "\"recommendedAction\":\"MANUAL_RETRY_OR_DISCARD_REPLICAS\"");
            Assert.assertTrue(queuesJson.contains("\"replicaCategorySummary\":\"CONFLICT=1\""));

            int replica1Baseline = replica1Calls.get();
            int replica2Baseline = replica2Calls.get();
            String discardResp = sendHttp(
                "POST",
                "http://127.0.0.1:" + monitor.getPort() + "/sync/api/failed/discard-replicas-by-category?category=CONFLICT",
                "");
            Assert.assertTrue(discardResp.contains("\"ok\":true"));
            Assert.assertTrue(discardResp.contains("\"categories\":[\"CONFLICT\"]"));
            assertJsonNumericFieldPositive(discardResp, "discardedReplicaCount");
            assertJsonFieldPresent(discardResp, "clearedFailedItemCount");
            assertJsonFieldPresent(discardResp, "clearedOutstandingReplicaCount");
            assertJsonFieldPresent(discardResp, "clearedReplicaCategorySummary");
            assertJsonFieldPresent(discardResp, "remainingFailedItemCount");
            assertJsonFieldPresent(discardResp, "remainingOutstandingReplicaCount");
            assertJsonFieldPresent(discardResp, "remainingReplicaCategorySummary");
            waitUntil(() -> !svc.getStore().fileCreatesFailed().contains(Long.valueOf(fileId)), 10, TimeUnit.SECONDS);
            waitForQueuesJsonContains(monitor.getPort(),
                "\"action\":\"DISCARD_REPLICAS_BY_CATEGORY\"",
                "\"phase\":\"operator_action\"",
                "\"clearedFailedItemCount\":2",
                "\"clearedOutstandingReplicaCount\":2",
                "\"clearedReplicaCategorySummary\":\"CONFLICT=2\"",
                "\"remainingFailedItemCount\":0",
                "\"remainingOutstandingReplicaCount\":0");
            Assert.assertEquals(replica1Baseline, replica1Calls.get());
            Assert.assertEquals(replica2Baseline, replica2Calls.get());
        }
    }

    @Test
    public void shouldDiscardRetryLimitReplicaViaMonitorCategoryActionOverTcp() throws Exception {
        long taskId = 109L;
        Path senderRoot = Files.createTempDirectory("p2p_sync_sender_root_retry_limit_category_");
        Path senderState = Files.createTempDirectory("p2p_sync_sender_state_retry_limit_category_");
        AtomicInteger replica1Calls = new AtomicInteger();
        AtomicInteger replica2Calls = new AtomicInteger();
        RetryOnlyHandler retryOnly = new RetryOnlyHandler();
        try (ReceiverNode receiver1 = ReceiverNode.start(taskId, 581);
             ReceiverNode receiver2 = ReceiverNode.start(taskId, 582);
             ManagedTcpHandler handler1 = ManagedTcpHandler.connect(taskId, receiver1.port);
             ManagedTcpHandler handler2 = ManagedTcpHandler.connect(taskId, receiver2.port);
             CountingHandler counted1 = new CountingHandler(handler1, replica1Calls);
             CountingHandler counted2 = new CountingHandler(handler2, replica2Calls);
             MultiEndpointRpcSyncEventHandler fanOut = MultiEndpointRpcSyncEventHandler.forHandlers(
                 taskId,
                 Arrays.asList(counted1, counted2, retryOnly));
             P2PDirectorySyncService svc = new P2PDirectorySyncService(senderConfig(taskId, senderRoot, senderState, 1, 0L), fanOut);
             P2PSyncMonitorServer monitor = new P2PSyncMonitorServer(svc, new InetSocketAddress("127.0.0.1", 0))) {
            svc.start();
            monitor.start();
            waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

            Path senderFile = senderRoot.resolve("retry-limit-hello.txt");
            writeUtf8(senderFile, "retry limit category sync");

            assertFileSynced(receiver1.root.resolve("retry-limit-hello.txt"), "retry limit category sync",
                Files.getLastModifiedTime(senderFile).toMillis());
            assertFileSynced(receiver2.root.resolve("retry-limit-hello.txt"), "retry limit category sync",
                Files.getLastModifiedTime(senderFile).toMillis());

            long fileId = svc.getStore().getOrCreateFileId("retry-limit-hello.txt");
            waitUntil(() -> svc.getStore().fileCreatesFailed().contains(Long.valueOf(fileId)), 10, TimeUnit.SECONDS);
            waitUntil(() -> "retry_limit_exceeded".equals(svc.getStore().getFailedReason(FileSyncEventType.CREATE, false, fileId)), 10, TimeUnit.SECONDS);

            String queuesJson = sendHttp("GET", "http://127.0.0.1:" + monitor.getPort() + "/sync/api/queues?limit=20", null);
            assertContainsInOrder(queuesJson, "\"reason\":\"RETRY_LIMIT\"", "\"recommendedAction\":\"MANUAL_RETRY_OR_DISCARD_REPLICAS\"");

            int replica1Baseline = replica1Calls.get();
            int replica2Baseline = replica2Calls.get();
            int retryBaseline = retryOnly.getAttempts();
            String discardResp = sendHttp(
                "POST",
                "http://127.0.0.1:" + monitor.getPort() + "/sync/api/failed/discard-replicas-by-category?category=RETRY_LIMIT",
                "");
            Assert.assertTrue(discardResp.contains("\"ok\":true"));
            Assert.assertTrue(discardResp.contains("\"categories\":[\"RETRY_LIMIT\"]"));
            assertJsonNumericFieldPositive(discardResp, "discardedReplicaCount");
            assertJsonFieldPresent(discardResp, "clearedFailedItemCount");
            assertJsonFieldPresent(discardResp, "clearedOutstandingReplicaCount");
            assertJsonFieldPresent(discardResp, "clearedReplicaCategorySummary");
            assertJsonFieldPresent(discardResp, "remainingFailedItemCount");
            assertJsonFieldPresent(discardResp, "remainingOutstandingReplicaCount");
            assertJsonFieldPresent(discardResp, "remainingReplicaCategorySummary");
            waitUntil(() -> !svc.getStore().fileCreatesFailed().contains(Long.valueOf(fileId)), 10, TimeUnit.SECONDS);
            waitForQueuesJsonContains(monitor.getPort(),
                "\"action\":\"DISCARD_REPLICAS_BY_CATEGORY\"",
                "\"phase\":\"operator_action\"",
                "\"clearedFailedItemCount\":2",
                "\"clearedOutstandingReplicaCount\":2",
                "\"clearedReplicaCategorySummary\":\"RETRY_LIMIT=2\"",
                "\"remainingFailedItemCount\":0",
                "\"remainingOutstandingReplicaCount\":0");
            Assert.assertEquals(replica1Baseline, replica1Calls.get());
            Assert.assertEquals(replica2Baseline, replica2Calls.get());
            Assert.assertEquals(retryBaseline, retryOnly.getAttempts());
        }
    }

    private static P2PSyncConfig senderConfig(long taskId, Path senderRoot, Path senderState) {
        P2PSyncConfig senderCfg = new P2PSyncConfig();
        senderCfg.setTaskId(taskId);
        senderCfg.setLocalDir(senderRoot.toString());
        senderCfg.setDsHome(senderState.toString());
        return senderCfg;
    }

    private static P2PSyncConfig senderConfig(long taskId, Path senderRoot, Path senderState, int maxRetryCount, long retryBackoffMillis) {
        P2PSyncConfig senderCfg = senderConfig(taskId, senderRoot, senderState);
        senderCfg.setMaxRetryCount(maxRetryCount);
        senderCfg.setRetryBackoffMillis(retryBackoffMillis);
        return senderCfg;
    }

    private static void assertFileSynced(Path receiverFile, String expectedContent, long expectedTs) throws Exception {
        waitUntil(() -> Files.isRegularFile(receiverFile), 10, TimeUnit.SECONDS);
        waitUntil(() -> expectedContent.equals(readUtf8(receiverFile)), 10, TimeUnit.SECONDS);
        waitUntil(() -> Math.abs(Files.getLastModifiedTime(receiverFile).toMillis() - expectedTs) <= 2_000L,
            10, TimeUnit.SECONDS);

        Assert.assertEquals(expectedContent, readUtf8(receiverFile));
        long actualTs = Files.getLastModifiedTime(receiverFile).toMillis();
        Assert.assertTrue("expected ts=" + expectedTs + ", actual ts=" + actualTs + ", delta=" + (actualTs - expectedTs),
            Math.abs(actualTs - expectedTs) <= 2_000L);
    }

    private static void assertFileBytesSynced(Path receiverFile, byte[] expectedBytes, long expectedTs) throws Exception {
        waitUntil(() -> Files.isRegularFile(receiverFile), 10, TimeUnit.SECONDS);
        waitUntil(() -> {
            try {
                return Files.size(receiverFile) == expectedBytes.length;
            } catch (Exception e) {
                return false;
            }
        }, 10, TimeUnit.SECONDS);
        waitUntil(() -> Math.abs(Files.getLastModifiedTime(receiverFile).toMillis() - expectedTs) <= 2_000L,
            10, TimeUnit.SECONDS);

        Assert.assertArrayEquals(expectedBytes, Files.readAllBytes(receiverFile));
        long actualTs = Files.getLastModifiedTime(receiverFile).toMillis();
        Assert.assertTrue("expected ts=" + expectedTs + ", actual ts=" + actualTs + ", delta=" + (actualTs - expectedTs),
            Math.abs(actualTs - expectedTs) <= 2_000L);
    }

    private static void assertPathAbsent(Path path) throws Exception {
        waitUntil(() -> !Files.exists(path), 10, TimeUnit.SECONDS);
        Assert.assertFalse(Files.exists(path));
    }

    private static void waitUntil(CheckedBooleanSupplier condition, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100L);
        }
        Assert.fail("condition not met within timeout");
    }

    private static void waitForStableValue(IntSupplier supplier, long quietMillis, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        int last = supplier.getAsInt();
        long stableSince = System.nanoTime();
        while (System.nanoTime() < deadline) {
            Thread.sleep(100L);
            int current = supplier.getAsInt();
            if (current != last) {
                last = current;
                stableSince = System.nanoTime();
                continue;
            }
            if (System.nanoTime() - stableSince >= TimeUnit.MILLISECONDS.toNanos(quietMillis)) {
                return;
            }
        }
        Assert.fail("value did not stabilize within timeout");
    }

    private static boolean hasReplicaState(P2PSyncStateStore store, FileSyncEventType type, boolean directory, long fileId, String label, String status) {
        for (P2PSyncStateStore.ReplicaState replicaState : store.getReplicaStates(type, directory, fileId)) {
            if (label.equals(replicaState.getLabel()) && status.equals(replicaState.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private static void assertContainsInOrder(String text, String first, String second) {
        int firstIndex = text.indexOf(first);
        Assert.assertTrue("missing fragment: " + first + ", text=" + text, firstIndex >= 0);
        int secondIndex = text.indexOf(second, firstIndex);
        Assert.assertTrue("missing ordered fragment: " + second + ", text=" + text, secondIndex > firstIndex);
    }

    private static void assertJsonNumericFieldPositive(String json, String fieldName) {
        String zeroFragment = "\"" + fieldName + "\":0";
        String fieldFragment = "\"" + fieldName + "\":";
        Assert.assertTrue("missing field " + fieldName + ", json=" + json, json.contains(fieldFragment));
        Assert.assertFalse("expected positive " + fieldName + ", json=" + json, json.contains(zeroFragment));
    }

    private static void assertJsonFieldPresent(String json, String fieldName) {
        String fieldFragment = "\"" + fieldName + "\":";
        Assert.assertTrue("missing field " + fieldName + ", json=" + json, json.contains(fieldFragment));
    }

    private static void waitForQueuesJsonContains(int monitorPort, String... fragments) throws Exception {
        AtomicReference<String> lastJson = new AtomicReference<>("");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String json = sendHttp("GET", "http://127.0.0.1:" + monitorPort + "/sync/api/queues?limit=20", null);
            lastJson.set(json);
            boolean matches = true;
            for (String fragment : fragments) {
                if (!json.contains(fragment)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return;
            }
            Thread.sleep(100L);
        }
        String json = lastJson.get();
        Assert.fail("queues json missing expected fragments=" + Arrays.toString(fragments) + ", json=" + json);
    }

    private static int randomTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void writeUtf8(Path path, String value) throws Exception {
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readUtf8(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String sendHttp(String method, String url, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
        }
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String response = readAll(in);
        Assert.assertTrue("unexpected status=" + status + ", body=" + response, status >= 200 && status < 300);
        return response;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int n;
            while ((n = input.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static FileSyncEventHandler failingHandler(String reason) {
        return (type, fileId, relativePath, absolutePath, directory, acker) -> acker.fail(reason);
    }

    private static final class CountingHandler implements FileSyncEventHandler, AutoCloseable {
        private final FileSyncEventHandler delegate;
        private final AtomicInteger calls;

        private CountingHandler(FileSyncEventHandler delegate, AtomicInteger calls) {
            this.delegate = delegate;
            this.calls = calls;
        }

        @Override
        public void handle(FileSyncEventType type, long fileId, String relativePath, Path absolutePath, boolean directory, FileSyncAcker acker) {
            calls.incrementAndGet();
            delegate.handle(type, fileId, relativePath, absolutePath, directory, acker);
        }

        @Override
        public void close() throws Exception {
            if (delegate instanceof AutoCloseable) {
                ((AutoCloseable) delegate).close();
            }
        }
    }

    private static final class RecoverableHandler implements FileSyncEventHandler {
        private final AtomicReference<FileSyncEventHandler> delegateRef = new AtomicReference<>();
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void handle(FileSyncEventType type, long fileId, String relativePath, Path absolutePath, boolean directory, FileSyncAcker acker) {
            attempts.incrementAndGet();
            FileSyncEventHandler delegate = delegateRef.get();
            if (delegate == null) {
                acker.fail("network_unreachable");
                return;
            }
            delegate.handle(type, fileId, relativePath, absolutePath, directory, acker);
        }

        private void recover(FileSyncEventHandler delegate) {
            delegateRef.set(delegate);
        }

        private int getAttemptCount() {
            return attempts.get();
        }
    }

    private static final class RetryOnlyHandler implements FileSyncEventHandler {
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void handle(FileSyncEventType type, long fileId, String relativePath, Path absolutePath, boolean directory, FileSyncAcker acker) {
            attempts.incrementAndGet();
            acker.retry();
        }

        private int getAttempts() {
            return attempts.get();
        }
    }

    private static final class ManagedTcpHandler implements FileSyncEventHandler, SyncUploadStatusProvider, AutoCloseable {
        private final P2PClientTcp client;
        private final RpcSyncEventHandler delegate;
        private volatile boolean closed;

        private ManagedTcpHandler(P2PClientTcp client, RpcSyncEventHandler delegate) {
            this.client = client;
            this.delegate = delegate;
        }

        private static ManagedTcpHandler connect(long taskId, int port) {
            P2PClientTcp client = new P2PClientTcp(new InetSocketAddress("127.0.0.1", port));
            client.newSendMesageExecutorToQueue();
            RpcSyncEventHandler delegate = new RpcSyncEventHandler(new P2PRpcClient(client), new P2PUtils(client), taskId);
            return new ManagedTcpHandler(client, delegate);
        }

        @Override
        public void handle(FileSyncEventType type, long fileId, String relativePath, Path absolutePath, boolean directory, FileSyncAcker acker) {
            delegate.handle(type, fileId, relativePath, absolutePath, directory, acker);
        }

        @Override
        public java.util.List<SyncUploadStatus> snapshotActiveUploads(int limit) {
            return delegate.snapshotActiveUploads(limit);
        }

        @Override
        public java.util.List<SyncUploadStatus> snapshotRecentCompletedUploads(int limit) {
            return delegate.snapshotRecentCompletedUploads(limit);
        }

        @Override
        public java.util.List<SyncUploadStatus> snapshotRecentFailedUploads(int limit) {
            return delegate.snapshotRecentFailedUploads(limit);
        }

        @Override
        public void close() throws Exception {
            if (closed) {
                return;
            }
            closed = true;
            delegate.close();
            client.shutdown();
        }
    }

    private static final class ReceiverNode implements AutoCloseable {
        private final Path root;
        private final int port;
        private final AutoCloseable registration;
        private final P2PServerTcp server;
        private final Thread thread;
        private volatile boolean closed;

        private ReceiverNode(Path root, int port, AutoCloseable registration, P2PServerTcp server, Thread thread) {
            this.root = root;
            this.port = port;
            this.registration = registration;
            this.server = server;
            this.thread = thread;
        }

        private static ReceiverNode start(long taskId, int storeId) throws Exception {
            int port = randomTcpPort();
            Path receiverRoot = Files.createTempDirectory("p2p_sync_receiver_root_");
            Path receiverState = Files.createTempDirectory("p2p_sync_receiver_state_");

            P2PSyncConfig receiverCfg = new P2PSyncConfig();
            receiverCfg.setTaskId(taskId);
            receiverCfg.setStoreId(storeId);
            receiverCfg.setListenPort(port);
            receiverCfg.setLocalDir(receiverRoot.toString());
            receiverCfg.setDsHome(receiverState.toString());

            AutoCloseable registration = SyncApplyEventRpcRegistration.register(receiverCfg);
            P2PServerTcp server = new P2PServerTcp(port);
            Thread serverThread = new Thread(server::start, "p2p-sync-e2e-server-" + port);
            serverThread.setDaemon(true);
            serverThread.start();
            Thread.sleep(1_000L);
            return new ReceiverNode(receiverRoot, port, registration, server, serverThread);
        }

        @Override
        public void close() throws Exception {
            if (closed) {
                return;
            }
            closed = true;
            try {
                server.stop();
            } finally {
                try {
                    thread.interrupt();
                    thread.join(2_000L);
                } finally {
                    registration.close();
                }
            }
        }
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
