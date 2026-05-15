package javax.net.p2p.filesync.app;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import javax.net.p2p.auth.config.AuthConfig;
import javax.net.p2p.filesync.config.P2PSyncConfig;
import javax.net.p2p.filesync.monitor.P2PSyncMonitorServer;
import javax.net.p2p.filesync.sync.P2PDirectorySyncService;
import javax.net.p2p.filesync.sync.rpc.MultiEndpointRpcSyncEventHandler;
import javax.net.p2p.filesync.sync.rpc.server.SyncApplyEventRpcRegistration;
import javax.net.p2p.server.P2PServerTcp;

public final class P2PSyncNodeMain {

    public static void main(String[] args) throws Exception {
        P2PSyncConfig cfg = P2PSyncConfig.load();
        logAuthStatus(AuthConfig.load());

        AutoCloseable receiver = SyncApplyEventRpcRegistration.register(cfg);
        P2PServerTcp server = new P2PServerTcp(cfg.getListenPort());
        Thread serverThread = new Thread(server::start, "p2p-sync-server");
        serverThread.setDaemon(false);
        serverThread.start();

        MultiEndpointRpcSyncEventHandler senderHandler = null;
        P2PDirectorySyncService syncService = null;
        P2PSyncMonitorServer monitorServer = null;
        if (cfg.getRemoteEndpoints() != null && !cfg.getRemoteEndpoints().isEmpty()) {
            List<InetSocketAddress> endpoints = parseEndpoints(cfg.getRemoteEndpoints());
            senderHandler = new MultiEndpointRpcSyncEventHandler(cfg.getTaskId(), endpoints);
            syncService = new P2PDirectorySyncService(cfg, senderHandler);
            syncService.start();
            if (cfg.getMonitorPort() > 0) {
                monitorServer = new P2PSyncMonitorServer(syncService, new InetSocketAddress("0.0.0.0", cfg.getMonitorPort()));
                monitorServer.start();
                System.out.println("p2p-sync monitor: http://127.0.0.1:" + cfg.getMonitorPort() + "/sync");
            }
        }

        MultiEndpointRpcSyncEventHandler finalSenderHandler = senderHandler;
        P2PDirectorySyncService finalSyncService = syncService;
        P2PSyncMonitorServer finalMonitorServer = monitorServer;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (finalMonitorServer != null) {
                    finalMonitorServer.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (finalSyncService != null) {
                    finalSyncService.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (finalSenderHandler != null) {
                    finalSenderHandler.close();
                }
            } catch (Exception ignored) {
            }
            try {
                receiver.close();
            } catch (Exception ignored) {
            }
            try {
                server.stop();
            } catch (Exception ignored) {
            }
        }, "p2p-sync-shutdown"));

        serverThread.join();
    }

    private static void logAuthStatus(AuthConfig auth) {
        String source = detectAuthSource();
        String keyDir = System.getProperty("p2p.key.dir");
        if (auth != null && auth.isEnabled()) {
            System.out.println("p2p-sync auth.enabled=true"
                + ", source=" + source
                + ", p2p.key.dir=" + (keyDir == null ? "" : keyDir));
        } else {
            System.out.println("p2p-sync auth.enabled=false"
                + ", source=" + source);
        }
    }

    private static String detectAuthSource() {
        String inline = System.getProperty("p2p.auth.inlineYaml");
        if (inline != null && !inline.isBlank()) {
            return "inline";
        }
        String path = System.getProperty("p2p.auth.yaml");
        if (path != null && !path.isBlank()) {
            return "file:" + path;
        }
        File local = new File(System.getProperty("user.dir", "."), "auth.yaml").getAbsoluteFile();
        if (local.exists() && local.isFile()) {
            return "default:" + local.getAbsolutePath();
        }
        return "none";
    }

    private static List<InetSocketAddress> parseEndpoints(List<String> endpoints) {
        List<InetSocketAddress> out = new ArrayList<>();
        for (String s : endpoints) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String v = s.trim();
            int idx = v.lastIndexOf(':');
            if (idx <= 0 || idx == v.length() - 1) {
                throw new IllegalArgumentException("invalid endpoint: " + s);
            }
            String host = v.substring(0, idx);
            int port = Integer.parseInt(v.substring(idx + 1));
            out.add(new InetSocketAddress(host, port));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("no valid endpoints");
        }
        return out;
    }
}
