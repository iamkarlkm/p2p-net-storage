package javax.net.p2p.filesync.monitor;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import javax.net.p2p.filesync.config.P2PSyncConfig;
import javax.net.p2p.filesync.sync.FileSyncEventType;
import javax.net.p2p.filesync.sync.P2PDirectorySyncService;
import javax.net.p2p.filesync.sync.P2PSyncStateStore;

public final class P2PSyncMonitorBrowserSmokeMain {

    private P2PSyncMonitorBrowserSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18090;
        Path root = Files.createTempDirectory("p2p_sync_monitor_browser_root_");
        Path state = Files.createTempDirectory("p2p_sync_monitor_browser_state_");

        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(200L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());

        final P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, null);
        svc.start();
        final P2PSyncStateStore store = svc.getStore();
        long retryId = store.getOrCreateFileId("retry.txt");
        long discardId = store.getOrCreateFileId("discard.txt");
        store.markFailed(FileSyncEventType.MODIFY, false, retryId, "write_conflict");
        store.markFailed(FileSyncEventType.DELETE, false, discardId, "stale");

        final P2PSyncMonitorServer server = new P2PSyncMonitorServer(svc, new InetSocketAddress("127.0.0.1", port));
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (Exception ignored) {
            }
            try {
                svc.close();
            } catch (Exception ignored) {
            }
        }, "p2p-sync-monitor-browser-shutdown"));

        System.out.println("P2P monitor smoke server started at http://127.0.0.1:" + server.getPort() + "/sync");
        new CountDownLatch(1).await();
    }
}
