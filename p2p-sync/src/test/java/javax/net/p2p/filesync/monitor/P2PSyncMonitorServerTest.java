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
        cfg.setMaxRetryCount(3);
        cfg.setRetryBackoffMillis(2500L);

        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, new StaticUploadStatusHandler())) {
            svc.start();
            P2PSyncStateStore store = svc.getStore();
            long fileId = store.getOrCreateFileId("failed.txt");
            long staleId = store.getOrCreateFileId("stale.txt");
            store.markFailed(FileSyncEventType.CREATE, false, fileId, "write_conflict");
            store.markFailed(FileSyncEventType.DELETE, false, staleId, "stale");
            store.markReplicaState(FileSyncEventType.CREATE, false, fileId, "node-a", "ACKED");
            store.markReplicaState(FileSyncEventType.CREATE, false, fileId, "node-b", "FAILED");
            store.markReplicaState(FileSyncEventType.DELETE, false, staleId, "node-c", "FAILED");
            store.incrementRetryCount(FileSyncEventType.CREATE, false, fileId);
            store.incrementRetryCount(FileSyncEventType.CREATE, false, fileId);
            store.markRetriedNow(FileSyncEventType.CREATE, false, fileId);
            store.incrementRetryCount(FileSyncEventType.DELETE, false, staleId);
            store.incrementRetryCount(FileSyncEventType.DELETE, false, staleId);
            store.enqueueFileModify(store.getOrCreateFileId("active.txt"));

            try (P2PSyncMonitorServer server = new P2PSyncMonitorServer(svc, new InetSocketAddress("127.0.0.1", 0))) {
                server.start();
                String index = send("GET", "http://127.0.0.1:" + server.getPort() + "/sync", null);
                Assert.assertTrue(index.contains("p2p-sync 队列监控"));
                Assert.assertTrue(index.contains("/sync/api/queues?limit=200"));
                Assert.assertTrue(index.contains("data-action"));
                Assert.assertTrue(index.contains("data-batch-action"));
                Assert.assertTrue(index.contains("批量重试可自动恢复副本"));
                Assert.assertTrue(index.contains("批量放弃人工介入副本"));
                Assert.assertTrue(index.contains("document.addEventListener('click'"));
                Assert.assertTrue(index.contains("class=\"page\""));
                Assert.assertTrue(index.contains("class=\"section\""));
                Assert.assertTrue(index.contains("总览区"));
                Assert.assertTrue(index.contains("失败区"));
                Assert.assertTrue(index.contains("上传区"));
                Assert.assertTrue(index.contains("队列明细区"));
                Assert.assertTrue(index.contains(">replicas</th>"));
                Assert.assertTrue(index.contains("副本恢复分级汇总"));
                Assert.assertTrue(index.contains("副本失败原因汇总"));
                Assert.assertTrue(index.contains(">replicaRecoveryClass</th>"));
                Assert.assertTrue(index.contains(">replicaReasons</th>"));

                String json = send("GET", "http://127.0.0.1:" + server.getPort() + "/sync/api/queues?limit=20", null);
                Assert.assertTrue(json.contains("\"ok\":true"));
                Assert.assertTrue(json.contains("\"failed_file_create\""));
                Assert.assertTrue(json.contains("\"failed_file_delete\""));
                Assert.assertTrue(json.contains("\"queueMatrix\""));
                Assert.assertTrue(json.contains("\"label\":\"文件新增\""));
                Assert.assertTrue(json.contains("\"label\":\"文件修改\""));
                Assert.assertTrue(json.contains("\"activeCount\":1"));
                Assert.assertTrue(json.contains("\"failedCount\":2"));
                Assert.assertTrue(json.contains("\"fileId\":\""));
                Assert.assertTrue(json.contains("\"path\":\"failed.txt\""));
                Assert.assertTrue(json.contains("\"retryCount\":2"));
                Assert.assertTrue(json.contains("\"remainingRetries\":1"));
                Assert.assertTrue(json.contains("\"retryable\":true"));
                Assert.assertTrue(json.contains("\"failedAtMillis\":"));
                Assert.assertTrue(json.contains("\"lastRetriedAtMillis\":"));
                Assert.assertTrue(json.contains("\"reason\":\"write_conflict\""));
                Assert.assertTrue(json.contains("\"replicaStates\""));
                Assert.assertTrue(json.contains("\"replicaSummary\":\"node-a=ACKED, node-b=FAILED\""));
                Assert.assertTrue(json.contains("\"replicaReasonSummary\":\"write_conflict=1\""));
                Assert.assertTrue(json.contains("\"replicaRecoveryClass\":\"MANUAL_INTERVENTION\""));
                Assert.assertTrue(json.contains("\"outstandingReplicaCount\":1"));
                Assert.assertTrue(json.contains("\"autoRecoverableReplicaCount\":0"));
                Assert.assertTrue(json.contains("\"manualReplicaCount\":1"));
                Assert.assertTrue(json.contains("\"replicaReasonItems\""));
                Assert.assertTrue(json.contains("\"label\":\"node-a\""));
                Assert.assertTrue(json.contains("\"status\":\"FAILED\""));
                Assert.assertTrue(json.contains("\"healthSummary\""));
                Assert.assertTrue(json.contains("\"activeCount\":1"));
                Assert.assertTrue(json.contains("\"failedCount\":2"));
                Assert.assertTrue(json.contains("\"uploadingCount\":1"));
                Assert.assertTrue(json.contains("\"oldestFailedAtMillis\":"));
                Assert.assertTrue(json.contains("\"maxRetryCount\":3"));
                Assert.assertTrue(json.contains("\"failureSummary\""));
                Assert.assertTrue(json.contains("\"totalFailedItems\":2"));
                Assert.assertTrue(json.contains("\"failureRecoverySummary\""));
                Assert.assertTrue(json.contains("\"recoveryClass\":\"MANUAL_INTERVENTION\""));
                Assert.assertTrue(json.contains("\"recoveryClass\":\"AUTO_RECOVERABLE\""));
                Assert.assertTrue(json.contains("\"replicaRecoverySummary\""));
                Assert.assertTrue(json.contains("\"totalOutstandingReplicas\":2"));
                Assert.assertTrue(json.contains("\"manualReplicaCount\":1"));
                Assert.assertTrue(json.contains("\"replicaFailureSummary\""));
                Assert.assertTrue(json.contains("\"reason\":\"write_conflict\""));
                Assert.assertTrue(json.contains("\"reason\":\"stale\""));
                Assert.assertTrue(json.contains("\"hotFailedItems\""));
                Assert.assertTrue(json.contains("\"size\":2"));
                Assert.assertTrue(json.contains("\"reason\":\"stale\""));
                Assert.assertTrue(json.contains("\"retryCount\":2"));
                Assert.assertTrue(json.contains("\"remainingRetries\":1"));
                Assert.assertTrue(json.contains("\"retryable\":true"));
                Assert.assertTrue(json.contains("\"recoveryClass\":\"AUTO_RECOVERABLE\""));
                Assert.assertTrue(json.contains("\"recoveryClass\":\"MANUAL_INTERVENTION\""));
                Assert.assertTrue(json.contains("\"count\":1"));
                Assert.assertTrue(json.contains("\"uploads\""));
                Assert.assertTrue(json.contains("\"uploadPolicy\""));
                Assert.assertTrue(json.contains("\"mode\":\"AUTO_SEGMENT_RESUMABLE\""));
                Assert.assertTrue(json.contains("\"uploadBlockSizeBytes\":"));
                Assert.assertTrue(json.contains("\"retryPolicy\""));
                Assert.assertTrue(json.contains("\"autoRetryMode\":\"LIMITED_WITH_BACKOFF\""));
                Assert.assertTrue(json.contains("\"maxRetryCount\":3"));
                Assert.assertTrue(json.contains("\"retryBackoffMillis\":2500"));
                Assert.assertTrue(json.contains("\"manualRetryUnrestricted\":true"));
                Assert.assertTrue(json.contains("\"recentTimeline\""));
                Assert.assertTrue(json.contains("\"phase\":\"completed\""));
                Assert.assertTrue(json.contains("\"phase\":\"failed\""));
                Assert.assertTrue(json.contains("\"size\":1"));
                Assert.assertTrue(json.contains("\"phase\":\"uploading\""));
                Assert.assertTrue(json.contains("\"segmented\":true"));
                Assert.assertTrue(json.contains("\"recentCompletedUploads\""));
                Assert.assertTrue(json.contains("\"recentFailedUploads\""));
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
        cfg.setMaxRetryCount(1);

        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, null)) {
            svc.start();
            P2PSyncStateStore store = svc.getStore();
            long retryId = store.getOrCreateFileId("retry.txt");
            long discardId = store.getOrCreateFileId("discard.txt");
            long targetedId = store.getOrCreateFileId("targeted.txt");
            store.markFailed(FileSyncEventType.MODIFY, false, retryId, "write_conflict");
            store.markFailed(FileSyncEventType.DELETE, false, discardId, "stale");
            store.markFailed(FileSyncEventType.CREATE, false, targetedId, "replicas_pending");
            store.markReplicaState(FileSyncEventType.CREATE, false, targetedId, "node-a", P2PSyncStateStore.REPLICA_ACKED);
            store.markReplicaState(FileSyncEventType.CREATE, false, targetedId, "node-b", P2PSyncStateStore.REPLICA_FAILED);
            store.markReplicaState(FileSyncEventType.CREATE, false, targetedId, "node-c", P2PSyncStateStore.REPLICA_FAILED);
            store.incrementRetryCount(FileSyncEventType.MODIFY, false, retryId);
            store.incrementRetryCount(FileSyncEventType.DELETE, false, discardId);

            try (P2PSyncMonitorServer server = new P2PSyncMonitorServer(svc, new InetSocketAddress("127.0.0.1", 0))) {
                server.start();
                String retryResp = send("POST",
                    "http://127.0.0.1:" + server.getPort() + "/sync/api/failed/retry?fileId=" + retryId + "&dir=false&type=MODIFY",
                    "");
                Assert.assertTrue(retryResp.contains("\"ok\":true"));
                Assert.assertFalse(store.fileModifiesFailed().contains(Long.valueOf(retryId)));
                Assert.assertTrue(store.fileModifiesActive().contains(Long.valueOf(retryId)));

                String discardResp = send("POST",
                    "http://127.0.0.1:" + server.getPort() + "/sync/api/failed/discard?fileId=" + discardId + "&dir=false&type=DELETE",
                    "");
                Assert.assertTrue(discardResp.contains("\"ok\":true"));
                Assert.assertFalse(store.fileDeletesFailed().contains(Long.valueOf(discardId)));
                Assert.assertEquals(0, store.getRetryCount(FileSyncEventType.DELETE, false, discardId));

                String targetedRetryResp = send("POST",
                    "http://127.0.0.1:" + server.getPort() + "/sync/api/failed/retry?fileId=" + targetedId
                        + "&dir=false&type=CREATE&replica=node-b",
                    "");
                Assert.assertTrue(targetedRetryResp.contains("\"ok\":true"));
                Assert.assertFalse(store.fileCreatesFailed().contains(Long.valueOf(targetedId)));
                Assert.assertTrue(hasReplicaState(store, FileSyncEventType.CREATE, false, targetedId, "node-b", P2PSyncStateStore.REPLICA_TARGETED));
                Assert.assertTrue(hasReplicaState(store, FileSyncEventType.CREATE, false, targetedId, "node-c", P2PSyncStateStore.REPLICA_FAILED));

                store.fileCreatesActive().remove(Long.valueOf(targetedId));
                store.fileCreatesActive().sync();
                store.markFailed(FileSyncEventType.CREATE, false, targetedId, "replicas_pending");

                String targetedDiscardResp = send("POST",
                    "http://127.0.0.1:" + server.getPort() + "/sync/api/failed/discard?fileId=" + targetedId
                        + "&dir=false&type=CREATE&replica=node-c",
                    "");
                Assert.assertTrue(targetedDiscardResp.contains("\"ok\":true"));
                Assert.assertTrue(store.fileCreatesFailed().contains(Long.valueOf(targetedId)));
                Assert.assertTrue(hasReplicaState(store, FileSyncEventType.CREATE, false, targetedId, "node-c", P2PSyncStateStore.REPLICA_DISCARDED));
            }
        }
    }

    @Test
    public void shouldBatchRetryAutoRecoverableReplicasViaHttp() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_monitor_root_batch_");
        Path state = Files.createTempDirectory("p2p_sync_monitor_state_batch_");
        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(102L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());
        cfg.setMaxRetryCount(2);

        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, null)) {
            svc.start();
            P2PSyncStateStore store = svc.getStore();
            long autoId = store.getOrCreateFileId("auto.txt");
            long manualId = store.getOrCreateFileId("manual.txt");
            long cappedId = store.getOrCreateFileId("capped.txt");
            store.markFailed(FileSyncEventType.CREATE, false, autoId, "stale");
            store.markReplicaState(FileSyncEventType.CREATE, false, autoId, "node-a", P2PSyncStateStore.REPLICA_FAILED);
            store.markReplicaState(FileSyncEventType.CREATE, false, autoId, "node-b", P2PSyncStateStore.REPLICA_FAILED);
            store.markFailed(FileSyncEventType.MODIFY, false, manualId, "write_conflict");
            store.markReplicaState(FileSyncEventType.MODIFY, false, manualId, "node-c", P2PSyncStateStore.REPLICA_FAILED);
            store.markFailed(FileSyncEventType.DELETE, false, cappedId, "stale");
            store.markReplicaState(FileSyncEventType.DELETE, false, cappedId, "node-d", P2PSyncStateStore.REPLICA_FAILED);
            store.incrementRetryCount(FileSyncEventType.DELETE, false, cappedId);
            store.incrementRetryCount(FileSyncEventType.DELETE, false, cappedId);

            try (P2PSyncMonitorServer server = new P2PSyncMonitorServer(svc, new InetSocketAddress("127.0.0.1", 0))) {
                server.start();
                String response = send("POST",
                    "http://127.0.0.1:" + server.getPort() + "/sync/api/failed/retry-auto-recoverable-replicas",
                    "");
                Assert.assertTrue(response.contains("\"ok\":true"));
                Assert.assertTrue(response.contains("\"touchedFileCount\":1"));
                Assert.assertTrue(response.contains("\"retriedReplicaCount\":2"));

                Assert.assertFalse(store.fileCreatesFailed().contains(Long.valueOf(autoId)));
                Assert.assertTrue(store.fileCreatesActive().contains(Long.valueOf(autoId)));
                Assert.assertTrue(hasReplicaState(store, FileSyncEventType.CREATE, false, autoId, "node-a", P2PSyncStateStore.REPLICA_TARGETED));
                Assert.assertTrue(hasReplicaState(store, FileSyncEventType.CREATE, false, autoId, "node-b", P2PSyncStateStore.REPLICA_TARGETED));

                Assert.assertTrue(store.fileModifiesFailed().contains(Long.valueOf(manualId)));
                Assert.assertTrue(hasReplicaState(store, FileSyncEventType.MODIFY, false, manualId, "node-c", P2PSyncStateStore.REPLICA_FAILED));

                Assert.assertTrue(store.fileDeletesFailed().contains(Long.valueOf(cappedId)));
                Assert.assertTrue(hasReplicaState(store, FileSyncEventType.DELETE, false, cappedId, "node-d", P2PSyncStateStore.REPLICA_FAILED));
            }
        }
    }

    @Test
    public void shouldBatchDiscardManualReplicasViaHttp() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_monitor_root_batch_discard_");
        Path state = Files.createTempDirectory("p2p_sync_monitor_state_batch_discard_");
        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(103L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());
        cfg.setMaxRetryCount(2);

        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, null)) {
            svc.start();
            P2PSyncStateStore store = svc.getStore();
            long manualId = store.getOrCreateFileId("manual.txt");
            long cappedId = store.getOrCreateFileId("capped.txt");
            long autoId = store.getOrCreateFileId("auto.txt");
            store.markFailed(FileSyncEventType.CREATE, false, manualId, "write_conflict");
            store.markReplicaState(FileSyncEventType.CREATE, false, manualId, "node-a", P2PSyncStateStore.REPLICA_FAILED);
            store.markReplicaState(FileSyncEventType.CREATE, false, manualId, "node-b", P2PSyncStateStore.REPLICA_FAILED);
            store.markFailed(FileSyncEventType.MODIFY, false, cappedId, "stale");
            store.markReplicaState(FileSyncEventType.MODIFY, false, cappedId, "node-c", P2PSyncStateStore.REPLICA_FAILED);
            store.incrementRetryCount(FileSyncEventType.MODIFY, false, cappedId);
            store.incrementRetryCount(FileSyncEventType.MODIFY, false, cappedId);
            store.markFailed(FileSyncEventType.DELETE, false, autoId, "stale");
            store.markReplicaState(FileSyncEventType.DELETE, false, autoId, "node-d", P2PSyncStateStore.REPLICA_FAILED);

            try (P2PSyncMonitorServer server = new P2PSyncMonitorServer(svc, new InetSocketAddress("127.0.0.1", 0))) {
                server.start();
                String response = send("POST",
                    "http://127.0.0.1:" + server.getPort() + "/sync/api/failed/discard-manual-replicas",
                    "");
                Assert.assertTrue(response.contains("\"ok\":true"));
                Assert.assertTrue(response.contains("\"touchedFileCount\":2"));
                Assert.assertTrue(response.contains("\"discardedReplicaCount\":3"));

                Assert.assertFalse(store.fileCreatesFailed().contains(Long.valueOf(manualId)));
                Assert.assertEquals(0, store.getReplicaStates(FileSyncEventType.CREATE, false, manualId).size());

                Assert.assertFalse(store.fileModifiesFailed().contains(Long.valueOf(cappedId)));
                Assert.assertEquals(0, store.getReplicaStates(FileSyncEventType.MODIFY, false, cappedId).size());

                Assert.assertTrue(store.fileDeletesFailed().contains(Long.valueOf(autoId)));
                Assert.assertTrue(hasReplicaState(store, FileSyncEventType.DELETE, false, autoId, "node-d", P2PSyncStateStore.REPLICA_FAILED));
            }
        }
    }

    private static boolean hasReplicaState(P2PSyncStateStore store, FileSyncEventType type, boolean directory, long fileId, String label, String status) {
        for (P2PSyncStateStore.ReplicaState replicaState : store.getReplicaStates(type, directory, fileId)) {
            if (label.equals(replicaState.getLabel()) && status.equals(replicaState.getStatus())) {
                return true;
            }
        }
        return false;
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
