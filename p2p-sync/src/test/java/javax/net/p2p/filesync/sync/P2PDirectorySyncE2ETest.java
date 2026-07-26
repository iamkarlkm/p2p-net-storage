package javax.net.p2p.filesync.sync;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

import javax.net.p2p.client.P2PClientTcp;
import javax.net.p2p.filesync.config.P2PSyncConfig;
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
        int port = randomTcpPort();
        long taskId = 101L;
        Path senderRoot = Files.createTempDirectory("p2p_sync_sender_root_");
        Path senderState = Files.createTempDirectory("p2p_sync_sender_state_");
        Path receiverRoot = Files.createTempDirectory("p2p_sync_receiver_root_");
        Path receiverState = Files.createTempDirectory("p2p_sync_receiver_state_");

        P2PServerTcp server = null;
        Thread serverThread = null;
        AutoCloseable receiverRegistration = null;
        P2PClientTcp client = null;
        RpcSyncEventHandler handler = null;

        P2PSyncConfig receiverCfg = new P2PSyncConfig();
        receiverCfg.setTaskId(taskId);
        receiverCfg.setStoreId(501);
        receiverCfg.setListenPort(port);
        receiverCfg.setLocalDir(receiverRoot.toString());
        receiverCfg.setDsHome(receiverState.toString());

        P2PSyncConfig senderCfg = new P2PSyncConfig();
        senderCfg.setTaskId(taskId);
        senderCfg.setLocalDir(senderRoot.toString());
        senderCfg.setDsHome(senderState.toString());

        try {
            receiverRegistration = SyncApplyEventRpcRegistration.register(receiverCfg);
            server = new P2PServerTcp(port);
            serverThread = new Thread(server::start, "p2p-sync-e2e-server");
            serverThread.setDaemon(true);
            serverThread.start();
            Thread.sleep(1_000L);

            client = new P2PClientTcp(new InetSocketAddress("127.0.0.1", port));
            client.newSendMesageExecutorToQueue();
            handler = new RpcSyncEventHandler(new P2PRpcClient(client), new P2PUtils(client), taskId);

            try (P2PDirectorySyncService svc = new P2PDirectorySyncService(senderCfg, handler)) {
                svc.start();
                waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

                Path senderFile = senderRoot.resolve("nested").resolve("hello.txt");
                Files.createDirectories(senderFile.getParent());
                long ts = System.currentTimeMillis() - 4_000L;
                writeUtf8(senderFile, "hello sync");
                Files.setLastModifiedTime(senderFile, FileTime.fromMillis(ts));

                Path receiverFile = receiverRoot.resolve("nested").resolve("hello.txt");
                waitUntil(() -> Files.isRegularFile(receiverFile), 10, TimeUnit.SECONDS);
                waitUntil(() -> "hello sync".equals(readUtf8(receiverFile)), 10, TimeUnit.SECONDS);
                waitUntil(() -> Math.abs(Files.getLastModifiedTime(receiverFile).toMillis() - ts) <= 2_000L,
                    10, TimeUnit.SECONDS);

                Assert.assertEquals("hello sync", readUtf8(receiverFile));
                long actualTs = Files.getLastModifiedTime(receiverFile).toMillis();
                Assert.assertTrue("expected ts=" + ts + ", actual ts=" + actualTs + ", delta=" + (actualTs - ts),
                    Math.abs(actualTs - ts) <= 2_000L);
            }
        } finally {
            if (handler != null) {
                handler.close();
            }
            if (client != null) {
                client.shutdown();
            }
            if (server != null) {
                server.stop();
            }
            if (serverThread != null) {
                serverThread.interrupt();
                serverThread.join(2_000L);
            }
            if (receiverRegistration != null) {
                receiverRegistration.close();
            }
        }
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

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
