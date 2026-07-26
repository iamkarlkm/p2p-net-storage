package javax.net.p2p.filesync.sync;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.p2p.client.P2PClientTcp;
import javax.net.p2p.filesync.config.P2PSyncConfig;
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
            Assert.assertTrue(replica1Baseline > 0);
            Assert.assertTrue(replica2Baseline > 0);
            Assert.assertTrue(recoverableBaseline > 0);

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
            Assert.assertEquals(recoverableBaseline + 1, recoverable.getAttemptCount());
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

    private static P2PSyncConfig senderConfig(long taskId, Path senderRoot, Path senderState) {
        P2PSyncConfig senderCfg = new P2PSyncConfig();
        senderCfg.setTaskId(taskId);
        senderCfg.setLocalDir(senderRoot.toString());
        senderCfg.setDsHome(senderState.toString());
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

    private static final class ManagedTcpHandler implements FileSyncEventHandler, AutoCloseable {
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
