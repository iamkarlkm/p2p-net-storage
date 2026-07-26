package javax.net.p2p.filesync.monitor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import javax.net.p2p.filesync.config.P2PSyncConfig;
import javax.net.p2p.filesync.sync.FileSyncAcker;
import javax.net.p2p.filesync.sync.FileSyncEventHandler;
import javax.net.p2p.filesync.sync.FileSyncEventType;
import javax.net.p2p.filesync.sync.P2PDirectorySyncService;
import javax.net.p2p.filesync.sync.P2PSyncStateStore;
import javax.net.p2p.filesync.sync.SyncUploadStatus;
import javax.net.p2p.filesync.sync.SyncUploadStatusProvider;

import org.junit.Assert;
import org.junit.Test;


public class P2PSyncMonitorServerTest {

    @Test
    public void shouldServeIndexAndQueueJson() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_monitor_root_");
        Path state = Files.createTempDirectory("p2p_sync_monitor_state_");
        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(100L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());

        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, new StaticUploadStatusHandler())) {
            svc.start();
            P2PSyncStateStore store = svc.getStore();
            long fileId = store.getOrCreateFileId("failed.txt");
            store.markFailed(FileSyncEventType.CREATE, false, fileId, "write_conflict");

            try (P2PSyncMonitorServer server = new P2PSyncMonitorServer(svc, new InetSocketAddress("127.0.0.1", 0))) {
                server.start();
                String index = send("GET", "http://127.0.0.1:" + server.getPort() + "/sync", null);
                Assert.assertTrue(index.contains("p2p-sync 队列监控"));
                Assert.assertTrue(index.contains("/sync/api/queues?limit=200"));
                Assert.assertTrue(index.contains("data-action"));
                Assert.assertTrue(index.contains("document.addEventListener('click'"));

                String json = send("GET", "http://127.0.0.1:" + server.getPort() + "/sync/api/queues?limit=20", null);
                Assert.assertTrue(json.contains("\"ok\":true"));
                Assert.assertTrue(json.contains("\"failed_file_create\""));
                Assert.assertTrue(json.contains("\"fileId\":\""));
                Assert.assertTrue(json.contains("\"path\":\"failed.txt\""));
                Assert.assertTrue(json.contains("\"reason\":\"write_conflict\""));
                Assert.assertTrue(json.contains("\"uploads\""));
                Assert.assertTrue(json.contains("\"size\":1"));
                Assert.assertTrue(json.contains("\"phase\":\"uploading\""));
                Assert.assertTrue(json.contains("\"segmented\":true"));
                Assert.assertTrue(json.contains("\"recentCompletedUploads\""));
                Assert.assertTrue(json.contains("\"recentFailedUploads\""));
                Assert.assertTrue(json.contains("\"phase\":\"completed\""));
                Assert.assertTrue(json.contains("\"phase\":\"failed\""));
                Assert.assertTrue(json.contains("\"message\":\"write_conflict\""));
            }
        }
    }

    @Test
    public void shouldRetryAndDiscardFailedItemsViaHttp() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_monitor_root_actions_");
        Path state = Files.createTempDirectory("p2p_sync_monitor_state_actions_");
        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(101L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());

        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, null)) {
            svc.start();
            P2PSyncStateStore store = svc.getStore();
            long retryId = store.getOrCreateFileId("retry.txt");
            long discardId = store.getOrCreateFileId("discard.txt");
            store.markFailed(FileSyncEventType.MODIFY, false, retryId, "write_conflict");
            store.markFailed(FileSyncEventType.DELETE, false, discardId, "stale");

            try (P2PSyncMonitorServer server = new P2PSyncMonitorServer(svc, new InetSocketAddress("127.0.0.1", 0))) {
                server.start();
                String retryResp = send("POST",
                    "http://127.0.0.1:" + server.getPort() + "/sync/api/failed/retry?fileId=" + retryId + "&dir=false&type=MODIFY",
                    "");
                Assert.assertTrue(retryResp.contains("\"ok\":true"));
                Assert.assertFalse(store.fileModifiesFailed().contains(Long.valueOf(retryId)));

                String discardResp = send("POST",
                    "http://127.0.0.1:" + server.getPort() + "/sync/api/failed/discard?fileId=" + discardId + "&dir=false&type=DELETE",
                    "");
                Assert.assertTrue(discardResp.contains("\"ok\":true"));
                Assert.assertFalse(store.fileDeletesFailed().contains(Long.valueOf(discardId)));
            }
        }
    }

    private static String send(String method, String url, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
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

    private static final class StaticUploadStatusHandler implements FileSyncEventHandler, SyncUploadStatusProvider {
        @Override
        public void handle(FileSyncEventType type, long fileId, String relativePath, Path absolutePath, boolean directory, FileSyncAcker acker) {
            acker.ack();
        }

        @Override
        public List<SyncUploadStatus> snapshotActiveUploads(int limit) {
            return Collections.singletonList(new SyncUploadStatus(
                1001L, 1002L, "big.bin", "uploading", 16L * 1024L * 1024L, true, 2, 1,
                System.currentTimeMillis() - 500L, System.currentTimeMillis()));
        }

        @Override
        public List<SyncUploadStatus> snapshotRecentCompletedUploads(int limit) {
            return Collections.singletonList(new SyncUploadStatus(
                2001L, 2002L, "done.bin", "completed", 8L * 1024L, false, 1, 1,
                System.currentTimeMillis() - 1000L, System.currentTimeMillis() - 800L, ""));
        }

        @Override
        public List<SyncUploadStatus> snapshotRecentFailedUploads(int limit) {
            return Collections.singletonList(new SyncUploadStatus(
                3001L, 3002L, "fail.bin", "failed", 4L * 1024L, false, 1, 0,
                System.currentTimeMillis() - 1500L, System.currentTimeMillis() - 1200L, "write_conflict"));
        }
    }
}
