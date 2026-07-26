package javax.net.p2p.filesync.monitor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.filesync.sync.FileSyncEventType;
import javax.net.p2p.filesync.sync.P2PDirectorySyncService;
import javax.net.p2p.filesync.sync.P2PSyncStateStore;
import javax.net.p2p.filesync.sync.P2PSyncStateStore.QueueKey;
import javax.net.p2p.filesync.sync.P2PSyncStateStore.QueueStage;
import javax.net.p2p.filesync.sync.PersistentLongQueue;
import javax.net.p2p.filesync.sync.SyncUploadStatus;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public final class P2PSyncMonitorServer implements AutoCloseable {

    private final HttpServer server;
    private final P2PDirectorySyncService syncService;

    public P2PSyncMonitorServer(P2PDirectorySyncService syncService, InetSocketAddress bind) throws IOException {
        this.syncService = syncService;
        this.server = HttpServer.create(bind, 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
        this.server.createContext("/sync", new IndexHandler());
        this.server.createContext("/sync/api/queues", new QueuesHandler());
        this.server.createContext("/sync/api/failed/retry", new FailedActionHandler(true));
        this.server.createContext("/sync/api/failed/discard", new FailedActionHandler(false));
        this.server.createContext("/sync/api/failed/retry-auto-recoverable-replicas", new BatchRetryAutoRecoverableReplicasHandler());
        this.server.createContext("/sync/api/failed/discard-manual-replicas", new BatchDiscardManualReplicasHandler());
        this.server.createContext("/sync/api/failed/retry-replicas-by-category", new BatchReplicaCategoryActionHandler(true));
        this.server.createContext("/sync/api/failed/discard-replicas-by-category", new BatchReplicaCategoryActionHandler(false));
    }

    public void start() {
        server.start();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private final class IndexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] body = indexHtml().getBytes(StandardCharsets.UTF_8);
            Headers h = exchange.getResponseHeaders();
            h.set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private final class QueuesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            P2PSyncStateStore store = syncService.getStore();
            if (store == null) {
                writeJson(exchange, 503, "{\"ok\":false,\"message\":\"store not ready\"}");
                return;
            }
            int limit = parseIntParam(exchange.getRequestURI(), "limit", 200);
            String json = buildQueuesJson(store, limit);
            writeJson(exchange, 200, json);
        }
    }

    private final class FailedActionHandler implements HttpHandler {

        private final boolean retry;

        private FailedActionHandler(boolean retry) {
            this.retry = retry;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            String type = param(uri, "type");
            String dir = param(uri, "dir");
            String fileIdStr = param(uri, "fileId");
            String replica = param(uri, "replica");
            long fileId = parseLong(fileIdStr, -1L);
            if (fileId <= 0L || type == null || type.isBlank()) {
                writeJson(exchange, 400, "{\"ok\":false,\"message\":\"missing params\"}");
                return;
            }
            boolean directory = "1".equals(dir) || "true".equalsIgnoreCase(dir);
            FileSyncEventType t = parseType(type);
            if (t == null) {
                writeJson(exchange, 400, "{\"ok\":false,\"message\":\"invalid type\"}");
                return;
            }
            P2PSyncStateStore store = syncService.getStore();
            if (store == null) {
                writeJson(exchange, 503, "{\"ok\":false,\"message\":\"store not ready\"}");
                return;
            }
            boolean ok;
            if (replica != null && !replica.isBlank()) {
                ok = retry
                    ? store.retryFailedReplica(t, directory, fileId, replica)
                    : store.discardFailedReplica(t, directory, fileId, replica);
            } else {
                ok = retry ? store.retryFailed(t, directory, fileId) : store.discardFailed(t, directory, fileId);
            }
            writeJson(exchange, 200, ok ? "{\"ok\":true}" : "{\"ok\":false,\"message\":\"not found\"}");
        }
    }

    private final class BatchRetryAutoRecoverableReplicasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            P2PSyncStateStore store = syncService.getStore();
            if (store == null) {
                writeJson(exchange, 503, "{\"ok\":false,\"message\":\"store not ready\"}");
                return;
            }
            BatchReplicaActionResult result = retryAutoRecoverableReplicas(store);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("ok", Boolean.TRUE);
            body.put("touchedFileCount", Integer.valueOf(result.touchedFileCount));
            body.put("retriedReplicaCount", Integer.valueOf(result.touchedReplicaCount));
            writeJson(exchange, 200, toJson(body));
        }
    }

    private final class BatchDiscardManualReplicasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            P2PSyncStateStore store = syncService.getStore();
            if (store == null) {
                writeJson(exchange, 503, "{\"ok\":false,\"message\":\"store not ready\"}");
                return;
            }
            BatchReplicaActionResult result = discardManualInterventionReplicas(store);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("ok", Boolean.TRUE);
            body.put("touchedFileCount", Integer.valueOf(result.touchedFileCount));
            body.put("discardedReplicaCount", Integer.valueOf(result.touchedReplicaCount));
            writeJson(exchange, 200, toJson(body));
        }
    }

    private final class BatchReplicaCategoryActionHandler implements HttpHandler {

        private final boolean retry;

        private BatchReplicaCategoryActionHandler(boolean retry) {
            this.retry = retry;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            P2PSyncStateStore store = syncService.getStore();
            if (store == null) {
                writeJson(exchange, 503, "{\"ok\":false,\"message\":\"store not ready\"}");
                return;
            }
            Set<String> categories = parseCategories(exchange.getRequestURI());
            if (categories.isEmpty()) {
                writeJson(exchange, 400, "{\"ok\":false,\"message\":\"missing category\"}");
                return;
            }
            BatchReplicaActionResult result = retry
                ? retryReplicasByCategory(store, categories)
                : discardReplicasByCategory(store, categories);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("ok", Boolean.TRUE);
            body.put("categories", new ArrayList<String>(categories));
            body.put("touchedFileCount", Integer.valueOf(result.touchedFileCount));
            body.put(retry ? "retriedReplicaCount" : "discardedReplicaCount", Integer.valueOf(result.touchedReplicaCount));
            writeJson(exchange, 200, toJson(body));
        }
    }

    private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        Headers h = exchange.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private String buildQueuesJson(P2PSyncStateStore store, int limit) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ok", Boolean.TRUE);
        root.put("queues", queuesToMap(store, limit));
        root.put("queueMatrix", queueMatrixToMap(store));
        root.put("healthSummary", healthSummaryToMap(store, limit));
        root.put("failureTrend", failureTrendToMap(store, limit));
        root.put("recoverySuccessSummary", recoverySuccessSummaryToMap(limit));
        root.put("failureSummary", failureSummaryToMap(store));
        root.put("failureRecoverySummary", failureRecoverySummaryToMap(store));
        root.put("replicaRecoverySummary", replicaRecoverySummaryToMap(store));
        root.put("replicaFailureSummary", replicaFailureSummaryToMap(store));
        root.put("replicaFailureCategorySummary", replicaFailureCategorySummaryToMap(store));
        root.put("hotFailedItems", hotFailedItemsToMap(store, limit));
        root.put("recentTimeline", recentTimelineToMap(limit));
        root.put("uploads", uploadsToMap(limit));
        root.put("uploadPolicy", uploadPolicyToMap());
        root.put("retryPolicy", retryPolicyToMap());
        root.put("recentCompletedUploads", uploadHistoryToMap(syncService.snapshotRecentCompletedUploads(limit)));
        root.put("recentFailedUploads", uploadHistoryToMap(syncService.snapshotRecentFailedUploads(limit)));
        return toJson(root);
    }

    private Map<String, Object> uploadPolicyToMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("mode", "AUTO_SEGMENT_RESUMABLE");
        out.put("uploadBlockSizeBytes", Integer.valueOf(P2PConfig.DATA_PUT_BLOCK_SIZE));
        out.put("resumeSupported", Boolean.TRUE);
        out.put("historyRetention", "memory_recent");
        return out;
    }

    private Map<String, Object> retryPolicyToMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("maxRetryCount", Integer.valueOf(syncService.getConfig().getMaxRetryCount()));
        out.put("retryBackoffMillis", Long.valueOf(syncService.getConfig().getRetryBackoffMillis()));
        out.put("manualRetryUnrestricted", Boolean.TRUE);
        out.put("autoRetryMode", "LIMITED_WITH_BACKOFF");
        return out;
    }

    private Map<String, Object> failureSummaryToMap(P2PSyncStateStore store) {
        Map<String, Integer> reasonCounts = new LinkedHashMap<String, Integer>();
        collectFailureReasons(reasonCounts, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false);
        collectFailureReasons(reasonCounts, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false);
        collectFailureReasons(reasonCounts, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false);
        collectFailureReasons(reasonCounts, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true);
        collectFailureReasons(reasonCounts, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true);

        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int total = 0;
        for (Entry<String, Integer> entry : reasonCounts.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            String reason = entry.getKey();
            item.put("reason", reason);
            item.put("count", entry.getValue());
            item.put("recommendedAction", recommendedActionForFailureReason(reason));
            item.put("operatorHint", operatorHintForFailureReason(reason));
            items.add(item);
            total += entry.getValue().intValue();
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("size", Integer.valueOf(items.size()));
        out.put("totalFailedItems", Integer.valueOf(total));
        out.put("items", items);
        return out;
    }

    private Map<String, Object> failureRecoverySummaryToMap(P2PSyncStateStore store) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        collectFailureRecoveryCounts(counts, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false);
        collectFailureRecoveryCounts(counts, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false);
        collectFailureRecoveryCounts(counts, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false);
        collectFailureRecoveryCounts(counts, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true);
        collectFailureRecoveryCounts(counts, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int total = 0;
        for (Entry<String, Integer> entry : counts.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("recoveryClass", entry.getKey());
            item.put("count", entry.getValue());
            items.add(item);
            total += entry.getValue().intValue();
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("size", Integer.valueOf(items.size()));
        out.put("totalFailedItems", Integer.valueOf(total));
        out.put("items", items);
        return out;
    }

    private Map<String, Object> replicaRecoverySummaryToMap(P2PSyncStateStore store) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        collectReplicaRecoveryCounts(counts, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false);
        collectReplicaRecoveryCounts(counts, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false);
        collectReplicaRecoveryCounts(counts, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false);
        collectReplicaRecoveryCounts(counts, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true);
        collectReplicaRecoveryCounts(counts, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int total = 0;
        for (Entry<String, Integer> entry : counts.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("recoveryClass", entry.getKey());
            item.put("count", entry.getValue());
            items.add(item);
            total += entry.getValue().intValue();
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("size", Integer.valueOf(items.size()));
        out.put("totalOutstandingReplicas", Integer.valueOf(total));
        out.put("items", items);
        return out;
    }

    private Map<String, Object> replicaFailureSummaryToMap(P2PSyncStateStore store) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        collectReplicaFailureReasons(counts, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false);
        collectReplicaFailureReasons(counts, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false);
        collectReplicaFailureReasons(counts, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false);
        collectReplicaFailureReasons(counts, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true);
        collectReplicaFailureReasons(counts, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int total = 0;
        for (Entry<String, Integer> entry : counts.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("reason", entry.getKey());
            item.put("count", entry.getValue());
            items.add(item);
            total += entry.getValue().intValue();
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("size", Integer.valueOf(items.size()));
        out.put("totalOutstandingReplicas", Integer.valueOf(total));
        out.put("items", items);
        return out;
    }

    private Map<String, Object> replicaFailureCategorySummaryToMap(P2PSyncStateStore store) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        collectReplicaFailureCategories(counts, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false);
        collectReplicaFailureCategories(counts, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false);
        collectReplicaFailureCategories(counts, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false);
        collectReplicaFailureCategories(counts, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true);
        collectReplicaFailureCategories(counts, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("size", Integer.valueOf(counts.size()));
        out.put("totalOutstandingReplicas", Integer.valueOf(totalCount(counts)));
        out.put("items", replicaCategoryItems(counts));
        return out;
    }

    private Set<String> parseCategories(URI uri) {
        String raw = param(uri, "category");
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> categories = new LinkedHashSet<String>();
        String[] parts = raw.split(",");
        for (String part : parts) {
            String category = normalizeReplicaCategoryToken(part);
            if (!category.isEmpty()) {
                categories.add(category);
            }
        }
        return categories;
    }

    private String normalizeReplicaCategoryToken(String category) {
        return category == null ? "" : category.trim().toUpperCase();
    }

    private Map<String, Object> queueMatrixToMap(P2PSyncStateStore store) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        items.add(queueMatrixRow("file_create", "文件新增",
            store.queueRef(QueueKey.FILE_CREATE, QueueStage.ACTIVE).size(),
            store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED).size()));
        items.add(queueMatrixRow("file_modify", "文件修改",
            store.queueRef(QueueKey.FILE_MODIFY, QueueStage.ACTIVE).size(),
            store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED).size()));
        items.add(queueMatrixRow("file_delete", "文件删除",
            store.queueRef(QueueKey.FILE_DELETE, QueueStage.ACTIVE).size(),
            store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED).size()));
        items.add(queueMatrixRow("dir_create", "目录新增",
            store.queueRef(QueueKey.DIR_CREATE, QueueStage.ACTIVE).size(),
            store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED).size()));
        items.add(queueMatrixRow("dir_delete", "目录删除",
            store.queueRef(QueueKey.DIR_DELETE, QueueStage.ACTIVE).size(),
            store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED).size()));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("size", Integer.valueOf(items.size()));
        out.put("items", items);
        return out;
    }

    private Map<String, Object> queueMatrixRow(String key, String label, int activeCount, int failedCount) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("key", key);
        row.put("label", label);
        row.put("activeCount", Integer.valueOf(activeCount));
        row.put("failedCount", Integer.valueOf(failedCount));
        return row;
    }

    private Map<String, Object> recentTimelineToMap(int limit) {
        List<SyncUploadStatus> timeline = new ArrayList<SyncUploadStatus>();
        timeline.addAll(syncService.snapshotRecentCompletedUploads(limit));
        timeline.addAll(syncService.snapshotRecentFailedUploads(limit));
        Collections.sort(timeline, new Comparator<SyncUploadStatus>() {
            @Override
            public int compare(SyncUploadStatus left, SyncUploadStatus right) {
                long leftTime = left.getUpdatedAtMillis();
                long rightTime = right.getUpdatedAtMillis();
                return leftTime < rightTime ? 1 : (leftTime == rightTime ? 0 : -1);
            }
        });
        if (timeline.size() > limit) {
            timeline = new ArrayList<SyncUploadStatus>(timeline.subList(0, limit));
        }
        return uploadHistoryToMap(timeline);
    }

    private Map<String, Object> hotFailedItemsToMap(P2PSyncStateStore store, int limit) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        collectHotFailedItems(items, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false);
        collectHotFailedItems(items, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false);
        collectHotFailedItems(items, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false);
        collectHotFailedItems(items, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true);
        collectHotFailedItems(items, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true);
        Collections.sort(items, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                int leftRetry = ((Integer) left.get("retryCount")).intValue();
                int rightRetry = ((Integer) right.get("retryCount")).intValue();
                if (leftRetry != rightRetry) {
                    return rightRetry - leftRetry;
                }
                long leftFailedAt = ((Long) left.get("failedAtMillis")).longValue();
                long rightFailedAt = ((Long) right.get("failedAtMillis")).longValue();
                if (leftFailedAt == 0L && rightFailedAt == 0L) {
                    return 0;
                }
                if (leftFailedAt == 0L) {
                    return 1;
                }
                if (rightFailedAt == 0L) {
                    return -1;
                }
                return leftFailedAt < rightFailedAt ? -1 : (leftFailedAt == rightFailedAt ? 0 : 1);
            }
        });
        if (items.size() > limit) {
            items = new ArrayList<Map<String, Object>>(items.subList(0, limit));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("size", Integer.valueOf(items.size()));
        out.put("items", items);
        return out;
    }

    private Map<String, Object> healthSummaryToMap(P2PSyncStateStore store, int limit) {
        int activeCount = 0;
        activeCount += store.queueRef(QueueKey.FILE_CREATE, QueueStage.ACTIVE).size();
        activeCount += store.queueRef(QueueKey.FILE_MODIFY, QueueStage.ACTIVE).size();
        activeCount += store.queueRef(QueueKey.FILE_DELETE, QueueStage.ACTIVE).size();
        activeCount += store.queueRef(QueueKey.DIR_CREATE, QueueStage.ACTIVE).size();
        activeCount += store.queueRef(QueueKey.DIR_DELETE, QueueStage.ACTIVE).size();

        HealthStats stats = new HealthStats();
        collectFailedHealth(stats, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false);
        collectFailedHealth(stats, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false);
        collectFailedHealth(stats, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false);
        collectFailedHealth(stats, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true);
        collectFailedHealth(stats, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("activeCount", Integer.valueOf(activeCount));
        out.put("failedCount", Integer.valueOf(stats.failedCount));
        out.put("uploadingCount", Integer.valueOf(syncService.snapshotActiveUploads(limit).size()));
        out.put("oldestFailedAtMillis", Long.valueOf(stats.oldestFailedAtMillis));
        out.put("maxRetryCount", Integer.valueOf(stats.maxRetryCount));
        return out;
    }

    private Map<String, Object> failureTrendToMap(P2PSyncStateStore store, int limit) {
        List<SyncUploadStatus> recentFailedUploads = syncService.snapshotRecentFailedUploads(limit);
        long nowMillis = System.currentTimeMillis();
        int failedLast5MinutesCount = 0;
        int failedLast60MinutesCount = 0;
        long latestFailedAtMillis = 0L;
        for (SyncUploadStatus upload : recentFailedUploads) {
            long updatedAtMillis = upload.getUpdatedAtMillis();
            if (updatedAtMillis <= 0L) {
                continue;
            }
            if (latestFailedAtMillis < updatedAtMillis) {
                latestFailedAtMillis = updatedAtMillis;
            }
            long ageMillis = nowMillis - updatedAtMillis;
            if (ageMillis <= TimeUnit.MINUTES.toMillis(5)) {
                failedLast5MinutesCount++;
            }
            if (ageMillis <= TimeUnit.MINUTES.toMillis(60)) {
                failedLast60MinutesCount++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("recentFailedCount", Integer.valueOf(recentFailedUploads.size()));
        out.put("failedLast5MinutesCount", Integer.valueOf(failedLast5MinutesCount));
        out.put("failedLast60MinutesCount", Integer.valueOf(failedLast60MinutesCount));
        out.put("outstandingFailedCount", Integer.valueOf(totalFailedCount(store)));
        out.put("latestFailedAtMillis", Long.valueOf(latestFailedAtMillis));
        return out;
    }

    private Map<String, Object> recoverySuccessSummaryToMap(int limit) {
        List<SyncUploadStatus> completedUploads = syncService.snapshotRecentCompletedUploads(limit);
        List<SyncUploadStatus> failedUploads = syncService.snapshotRecentFailedUploads(limit);
        long completedDurationTotal = 0L;
        long failedDurationTotal = 0L;
        long lastCompletedAtMillis = 0L;
        long lastFailedAtMillis = 0L;
        for (SyncUploadStatus upload : completedUploads) {
            completedDurationTotal += durationMillis(upload);
            if (lastCompletedAtMillis < upload.getUpdatedAtMillis()) {
                lastCompletedAtMillis = upload.getUpdatedAtMillis();
            }
        }
        for (SyncUploadStatus upload : failedUploads) {
            failedDurationTotal += durationMillis(upload);
            if (lastFailedAtMillis < upload.getUpdatedAtMillis()) {
                lastFailedAtMillis = upload.getUpdatedAtMillis();
            }
        }
        int completedCount = completedUploads.size();
        int failedCount = failedUploads.size();
        int totalCount = completedCount + failedCount;
        int successRatePercent = totalCount <= 0 ? 0 : (completedCount * 100) / totalCount;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("completedCount", Integer.valueOf(completedCount));
        out.put("failedCount", Integer.valueOf(failedCount));
        out.put("totalCount", Integer.valueOf(totalCount));
        out.put("successRatePercent", Integer.valueOf(successRatePercent));
        out.put("avgCompletedDurationMillis", Long.valueOf(completedCount <= 0 ? 0L : completedDurationTotal / completedCount));
        out.put("avgFailedDurationMillis", Long.valueOf(failedCount <= 0 ? 0L : failedDurationTotal / failedCount));
        out.put("lastCompletedAtMillis", Long.valueOf(lastCompletedAtMillis));
        out.put("lastFailedAtMillis", Long.valueOf(lastFailedAtMillis));
        return out;
    }

    private int totalFailedCount(P2PSyncStateStore store) {
        int failedCount = 0;
        failedCount += store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED).size();
        failedCount += store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED).size();
        failedCount += store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED).size();
        failedCount += store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED).size();
        failedCount += store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED).size();
        return failedCount;
    }

    private long durationMillis(SyncUploadStatus upload) {
        if (upload == null) {
            return 0L;
        }
        long startedAtMillis = upload.getStartedAtMillis();
        long updatedAtMillis = upload.getUpdatedAtMillis();
        if (startedAtMillis <= 0L || updatedAtMillis <= startedAtMillis) {
            return 0L;
        }
        return updatedAtMillis - startedAtMillis;
    }

    private void collectFailureReasons(Map<String, Integer> reasonCounts, P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir) {
        for (Long o : set) {
            long fileId = o.longValue();
            String reason = store.getFailedReason(type, dir, fileId);
            if (reason == null || reason.trim().isEmpty()) {
                reason = "unknown";
            }
            Integer current = reasonCounts.get(reason);
            reasonCounts.put(reason, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
        }
    }

    private void collectFailureRecoveryCounts(Map<String, Integer> counts, P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir) {
        for (Long o : set) {
            long fileId = o.longValue();
            int retryCount = store.getRetryCount(type, dir, fileId);
            String reason = store.getFailedReason(type, dir, fileId);
            String recoveryClass = recoveryClass(reason, retryCount);
            Integer current = counts.get(recoveryClass);
            counts.put(recoveryClass, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
        }
    }

    private void collectReplicaRecoveryCounts(Map<String, Integer> counts, P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir) {
        for (Long o : set) {
            long fileId = o.longValue();
            int retryCount = store.getRetryCount(type, dir, fileId);
            String reason = store.getFailedReason(type, dir, fileId);
            ReplicaRecoveryStats stats = replicaRecoveryStats(store, type, dir, fileId, retryCount, reason);
            addCount(counts, "AUTO_RECOVERABLE", stats.autoRecoverableReplicaCount);
            addCount(counts, "MANUAL_INTERVENTION", stats.manualReplicaCount);
        }
    }

    private void collectReplicaFailureReasons(Map<String, Integer> counts, P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir) {
        for (Long o : set) {
            long fileId = o.longValue();
            Map<String, Integer> reasonCounts = replicaReasonCounts(store, type, dir, fileId);
            for (Entry<String, Integer> entry : reasonCounts.entrySet()) {
                addCount(counts, entry.getKey(), entry.getValue().intValue());
            }
        }
    }

    private void collectReplicaFailureCategories(Map<String, Integer> counts, P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir) {
        for (Long o : set) {
            long fileId = o.longValue();
            Map<String, Integer> reasonCounts = replicaReasonCounts(store, type, dir, fileId);
            Map<String, Integer> categoryCounts = replicaReasonCategoryCounts(reasonCounts);
            for (Entry<String, Integer> entry : categoryCounts.entrySet()) {
                addCount(counts, entry.getKey(), entry.getValue().intValue());
            }
        }
    }

    private void collectFailedHealth(HealthStats stats, P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir) {
        for (Long o : set) {
            long fileId = o.longValue();
            stats.failedCount++;
            int retryCount = store.getRetryCount(type, dir, fileId);
            if (retryCount > stats.maxRetryCount) {
                stats.maxRetryCount = retryCount;
            }
            long failedAtMillis = store.getFailedAtMillis(type, dir, fileId);
            if (failedAtMillis > 0L && (stats.oldestFailedAtMillis == 0L || failedAtMillis < stats.oldestFailedAtMillis)) {
                stats.oldestFailedAtMillis = failedAtMillis;
            }
        }
    }

    private void collectHotFailedItems(List<Map<String, Object>> items, P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir) {
        for (Long o : set) {
            long fileId = o.longValue();
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("fileId", Long.toString(fileId));
            item.put("dir", Boolean.valueOf(dir));
            item.put("type", type.name());
            String path = store.getRelativePath(fileId);
            item.put("path", path == null ? "" : path);
            int retryCount = store.getRetryCount(type, dir, fileId);
            item.put("retryCount", Integer.valueOf(retryCount));
            item.put("remainingRetries", Integer.valueOf(remainingRetries(retryCount)));
            item.put("retryable", Boolean.valueOf(isRetryable(retryCount)));
            item.put("failedAtMillis", Long.valueOf(store.getFailedAtMillis(type, dir, fileId)));
            String reason = store.getFailedReason(type, dir, fileId);
            item.put("reason", reason == null ? "" : reason);
            item.put("recoveryClass", recoveryClass(reason, retryCount));
            List<Map<String, Object>> replicaStates = replicaStatesToMap(store, type, dir, fileId);
            item.put("replicaStates", replicaStates);
            item.put("replicaSummary", replicaSummary(replicaStates));
            ReplicaRecoveryStats replicaStats = replicaRecoveryStats(store, type, dir, fileId, retryCount, reason);
            item.put("replicaRecoveryClass", replicaStats.recoveryClass);
            item.put("outstandingReplicaCount", Integer.valueOf(replicaStats.outstandingReplicaCount));
            item.put("autoRecoverableReplicaCount", Integer.valueOf(replicaStats.autoRecoverableReplicaCount));
            item.put("manualReplicaCount", Integer.valueOf(replicaStats.manualReplicaCount));
            Map<String, Integer> replicaReasonCounts = replicaReasonCounts(store, type, dir, fileId);
            item.put("replicaReasonSummary", reasonSummary(replicaReasonCounts));
            item.put("replicaReasonItems", reasonItems(replicaReasonCounts));
            Map<String, Integer> replicaCategoryCounts = replicaReasonCategoryCounts(replicaReasonCounts);
            item.put("replicaCategorySummary", reasonSummary(replicaCategoryCounts));
            item.put("replicaCategoryItems", reasonItems(replicaCategoryCounts));
            item.put("recommendedAction", recommendedActionForHotFailedItem(reason, replicaCategoryCounts, replicaStats));
            item.put("operatorHint", operatorHintForHotFailedItem(reason, replicaCategoryCounts, replicaStats));
            items.add(item);
        }
    }

    private Map<String, Object> uploadsToMap(int limit) {
        return uploadHistoryToMap(syncService.snapshotActiveUploads(limit));
    }

    private Map<String, Object> uploadHistoryToMap(List<SyncUploadStatus> uploads) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (SyncUploadStatus upload : uploads) {
            items.add(uploadToMap(upload));
        }
        out.put("size", Integer.valueOf(items.size()));
        out.put("items", items);
        return out;
    }

    private Map<String, Object> uploadToMap(SyncUploadStatus upload) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("eventUid", Long.toString(upload.getEventUid()));
        item.put("fileId", Long.toString(upload.getFileId()));
        item.put("path", upload.getPath());
        item.put("phase", upload.getPhase());
        item.put("fileSize", Long.valueOf(upload.getFileSize()));
        item.put("segmented", Boolean.valueOf(upload.isSegmented()));
        item.put("totalSegments", Integer.valueOf(upload.getTotalSegments()));
        item.put("uploadedSegments", Integer.valueOf(upload.getUploadedSegments()));
        item.put("startedAtMillis", Long.valueOf(upload.getStartedAtMillis()));
        item.put("updatedAtMillis", Long.valueOf(upload.getUpdatedAtMillis()));
        item.put("message", upload.getMessage() == null ? "" : upload.getMessage());
        return item;
    }

    private int remainingRetries(int retryCount) {
        int maxRetryCount = syncService.getConfig().getMaxRetryCount();
        int remaining = maxRetryCount - retryCount;
        return remaining <= 0 ? 0 : remaining;
    }

    private boolean isRetryable(int retryCount) {
        return remainingRetries(retryCount) > 0;
    }

    private String recoveryClass(String reason, int retryCount) {
        if (reason == null) {
            reason = "";
        }
        if ("write_conflict".equals(reason) || "retry_limit_exceeded".equals(reason)) {
            return "MANUAL_INTERVENTION";
        }
        return isRetryable(retryCount) ? "AUTO_RECOVERABLE" : "MANUAL_INTERVENTION";
    }

    private ReplicaRecoveryStats replicaRecoveryStats(P2PSyncStateStore store, FileSyncEventType type, boolean dir, long fileId, int retryCount, String reason) {
        boolean autoRetryable = isRetryable(retryCount);
        boolean manualReason = "write_conflict".equals(reason) || "retry_limit_exceeded".equals(reason);
        int outstanding = 0;
        int autoRecoverable = 0;
        int manual = 0;
        for (P2PSyncStateStore.ReplicaState replicaState : store.getReplicaStates(type, dir, fileId)) {
            String status = replicaState.getStatus();
            if (!isReplicaActionable(status)) {
                continue;
            }
            outstanding++;
            if (isReplicaScheduledStatus(status)) {
                if (manualReason || !autoRetryable) {
                    manual++;
                } else {
                    autoRecoverable++;
                }
                continue;
            }
            if (manualReason || !autoRetryable) {
                manual++;
            } else {
                autoRecoverable++;
            }
        }
        return new ReplicaRecoveryStats(outstanding, autoRecoverable, manual);
    }

    private Map<String, Integer> replicaReasonCounts(P2PSyncStateStore store, FileSyncEventType type, boolean dir, long fileId) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        int retryCount = store.getRetryCount(type, dir, fileId);
        String reason = store.getFailedReason(type, dir, fileId);
        String reasonReplica = extractReplicaLabel(reason);
        String normalizedReason = normalizeReplicaReason(reason, retryCount);
        boolean autoRetryable = isRetryable(retryCount);
        boolean manualReason = "write_conflict".equals(normalizedReason) || "retry_limit_exceeded".equals(normalizedReason);
        for (P2PSyncStateStore.ReplicaState replicaState : store.getReplicaStates(type, dir, fileId)) {
            String status = replicaState.getStatus();
            if (!isReplicaActionable(status)) {
                continue;
            }
            String replicaReason = resolveReplicaReason(replicaState.getLabel(), status, reasonReplica, normalizedReason, manualReason, autoRetryable);
            addCount(counts, replicaReason, 1);
        }
        return counts;
    }

    private String resolveReplicaReason(String label, String status, String reasonReplica, String normalizedReason, boolean manualReason, boolean autoRetryable) {
        if (isReplicaScheduledStatus(status)) {
            if (manualReason || !autoRetryable) {
                return normalizedReason;
            }
            return "retry_scheduled";
        }
        if (!P2PSyncStateStore.REPLICA_FAILED.equals(status)) {
            return normalizedReason;
        }
        if (reasonReplica != null && reasonReplica.equals(label)) {
            return normalizedReason;
        }
        if (!manualReason && !autoRetryable) {
            return "retry_limit_exceeded";
        }
        return normalizedReason;
    }

    private List<Map<String, Object>> reasonItems(Map<String, Integer> counts) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Entry<String, Integer> entry : counts.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("reason", entry.getKey());
            item.put("count", entry.getValue());
            items.add(item);
        }
        return items;
    }

    private List<Map<String, Object>> replicaCategoryItems(Map<String, Integer> counts) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Entry<String, Integer> entry : counts.entrySet()) {
            String category = entry.getKey();
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("reason", category);
            item.put("count", entry.getValue());
            item.put("recommendedAction", recommendedActionForReplicaCategory(category));
            item.put("operatorHint", operatorHintForReplicaCategory(category));
            items.add(item);
        }
        return items;
    }

    private int totalCount(Map<String, Integer> counts) {
        int total = 0;
        for (Integer value : counts.values()) {
            total += value.intValue();
        }
        return total;
    }

    private String reasonSummary(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        for (Entry<String, Integer> entry : counts.entrySet()) {
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(entry.getKey());
            summary.append('=');
            summary.append(entry.getValue());
        }
        return summary.toString();
    }

    private Map<String, Integer> replicaReasonCategoryCounts(Map<String, Integer> reasonCounts) {
        Map<String, Integer> categoryCounts = new LinkedHashMap<String, Integer>();
        for (Entry<String, Integer> entry : reasonCounts.entrySet()) {
            addCount(categoryCounts, replicaReasonCategory(entry.getKey()), entry.getValue().intValue());
        }
        return categoryCounts;
    }

    private String normalizeReplicaReason(String reason, int retryCount) {
        String raw = reason == null ? "" : reason.trim();
        int marker = raw.indexOf(" [replica=");
        if (marker >= 0) {
            raw = raw.substring(0, marker).trim();
        }
        if (raw.isEmpty()) {
            return isRetryable(retryCount) ? "unknown" : "retry_limit_exceeded";
        }
        return raw;
    }

    private String replicaReasonCategory(String reason) {
        String raw = reason == null ? "" : reason.trim().toLowerCase();
        if (raw.isEmpty() || "unknown".equals(raw)) {
            return "UNKNOWN";
        }
        if ("retry_scheduled".equals(raw)) {
            return "RETRY_SCHEDULED";
        }
        if ("retry_limit_exceeded".equals(raw)) {
            return "RETRY_LIMIT";
        }
        if ("write_conflict".equals(raw) || raw.contains("conflict")) {
            return "CONFLICT";
        }
        if ("stale".equals(raw) || "replicas_pending".equals(raw) || raw.contains("stale") || raw.contains("pending")) {
            return "STATE_MISMATCH";
        }
        if (raw.contains("network") || raw.contains("timeout") || raw.contains("connection")
            || raw.contains("refused") || raw.contains("unreachable")) {
            return "NETWORK";
        }
        return "OTHER";
    }

    private String recommendedActionForFailureReason(String reason) {
        String raw = normalizeReasonToken(reason);
        if ("write_conflict".equals(raw) || raw.contains("conflict")) {
            return "MANUAL_RETRY_OR_DISCARD";
        }
        if ("retry_limit_exceeded".equals(raw)) {
            return "MANUAL_RETRY_OR_DISCARD";
        }
        if ("stale".equals(raw) || "replicas_pending".equals(raw) || raw.contains("pending")) {
            return "RETRY_AFTER_STATE_CHECK";
        }
        if (raw.contains("network") || raw.contains("timeout") || raw.contains("connection")
            || raw.contains("refused") || raw.contains("unreachable")) {
            return "RESTORE_CONNECTIVITY_AND_RETRY";
        }
        if ("retry_scheduled".equals(raw)) {
            return "WAIT_AUTO_RETRY";
        }
        return "INSPECT_AND_DECIDE";
    }

    private String operatorHintForFailureReason(String reason) {
        String action = recommendedActionForFailureReason(reason);
        if ("MANUAL_RETRY_OR_DISCARD".equals(action)) {
            return "检查冲突或封顶原因后手动重试；确认无需保留时可放弃该项。";
        }
        if ("RETRY_AFTER_STATE_CHECK".equals(action)) {
            return "先核对源端与目标端状态是否一致，再执行人工重试。";
        }
        if ("RESTORE_CONNECTIVITY_AND_RETRY".equals(action)) {
            return "先恢复网络或目标节点可达性，再执行重试。";
        }
        if ("WAIT_AUTO_RETRY".equals(action)) {
            return "当前仍处于自动重试调度中，优先观察下一轮执行结果。";
        }
        return "先查看失败明细与日志，再决定重试、放弃或人工修复。";
    }

    private String recommendedActionForReplicaCategory(String category) {
        String raw = normalizeReasonToken(category).toUpperCase();
        if ("NETWORK".equals(raw)) {
            return "RETRY_NETWORK_REPLICAS";
        }
        if ("CONFLICT".equals(raw) || "RETRY_LIMIT".equals(raw)) {
            return "MANUAL_RETRY_OR_DISCARD_REPLICAS";
        }
        if ("STATE_MISMATCH".equals(raw)) {
            return "VERIFY_STATE_THEN_RETRY";
        }
        if ("RETRY_SCHEDULED".equals(raw)) {
            return "WAIT_AUTO_RETRY";
        }
        return "INSPECT_REPLICA_DETAIL";
    }

    private String operatorHintForReplicaCategory(String category) {
        String action = recommendedActionForReplicaCategory(category);
        if ("RETRY_NETWORK_REPLICAS".equals(action)) {
            return "优先修复网络后批量重试 NETWORK 副本。";
        }
        if ("MANUAL_RETRY_OR_DISCARD_REPLICAS".equals(action)) {
            return "对冲突或封顶副本执行人工重试；确认无需同步时可批量放弃。";
        }
        if ("VERIFY_STATE_THEN_RETRY".equals(action)) {
            return "先核对副本状态或 pending 情况，再按需重试。";
        }
        if ("WAIT_AUTO_RETRY".equals(action)) {
            return "这些副本仍在自动调度中，先观察自动恢复。";
        }
        return "查看副本级失败明细后再决定下一步动作。";
    }

    private String recommendedActionForHotFailedItem(String reason, Map<String, Integer> replicaCategoryCounts, ReplicaRecoveryStats replicaStats) {
        if (replicaCategoryCounts != null && !replicaCategoryCounts.isEmpty()) {
            if (replicaCategoryCounts.containsKey("NETWORK")) {
                return recommendedActionForReplicaCategory("NETWORK");
            }
            if (replicaCategoryCounts.containsKey("CONFLICT")) {
                return recommendedActionForReplicaCategory("CONFLICT");
            }
            if (replicaCategoryCounts.containsKey("RETRY_LIMIT")) {
                return recommendedActionForReplicaCategory("RETRY_LIMIT");
            }
            if (replicaCategoryCounts.containsKey("STATE_MISMATCH")) {
                return recommendedActionForReplicaCategory("STATE_MISMATCH");
            }
        }
        if (replicaStats != null && "AUTO_RECOVERABLE".equals(replicaStats.recoveryClass)) {
            return "RETRY_FAILED_ITEM";
        }
        return recommendedActionForFailureReason(reason);
    }

    private String operatorHintForHotFailedItem(String reason, Map<String, Integer> replicaCategoryCounts, ReplicaRecoveryStats replicaStats) {
        String action = recommendedActionForHotFailedItem(reason, replicaCategoryCounts, replicaStats);
        if ("RETRY_FAILED_ITEM".equals(action)) {
            return "该项仍可恢复，优先执行重试并继续观察副本收敛。";
        }
        if ("RETRY_NETWORK_REPLICAS".equals(action) || "MANUAL_RETRY_OR_DISCARD_REPLICAS".equals(action)
            || "VERIFY_STATE_THEN_RETRY".equals(action) || "WAIT_AUTO_RETRY".equals(action)
            || "INSPECT_REPLICA_DETAIL".equals(action)) {
            String category = firstReplicaCategory(replicaCategoryCounts);
            return operatorHintForReplicaCategory(category == null ? "" : category);
        }
        return operatorHintForFailureReason(reason);
    }

    private String firstReplicaCategory(Map<String, Integer> replicaCategoryCounts) {
        if (replicaCategoryCounts == null || replicaCategoryCounts.isEmpty()) {
            return null;
        }
        return replicaCategoryCounts.entrySet().iterator().next().getKey();
    }

    private String normalizeReasonToken(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String extractReplicaLabel(String reason) {
        if (reason == null) {
            return null;
        }
        int start = reason.indexOf("[replica=");
        if (start < 0) {
            return null;
        }
        int valueStart = start + "[replica=".length();
        int end = reason.indexOf(']', valueStart);
        if (end <= valueStart) {
            return null;
        }
        String label = reason.substring(valueStart, end).trim();
        return label.isEmpty() ? null : label;
    }

    private BatchReplicaActionResult retryAutoRecoverableReplicas(P2PSyncStateStore store) {
        BatchReplicaActionResult result = new BatchReplicaActionResult();
        retryAutoRecoverableReplicas(result, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false);
        retryAutoRecoverableReplicas(result, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false);
        retryAutoRecoverableReplicas(result, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false);
        retryAutoRecoverableReplicas(result, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true);
        retryAutoRecoverableReplicas(result, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true);
        return result;
    }

    private void retryAutoRecoverableReplicas(BatchReplicaActionResult result, P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir) {
        List<Long> fileIds = new ArrayList<Long>();
        for (Long o : set) {
            fileIds.add(o);
        }
        for (Long fileIdRef : fileIds) {
            long fileId = fileIdRef.longValue();
            List<String> labels = autoRecoverableReplicaLabels(store, type, dir, fileId);
            if (labels.isEmpty()) {
                continue;
            }
            int updated = store.retryFailedReplicas(type, dir, fileId, labels);
            if (updated > 0) {
                result.touchedFileCount++;
                result.touchedReplicaCount += updated;
            }
        }
    }

    private BatchReplicaActionResult discardManualInterventionReplicas(P2PSyncStateStore store) {
        BatchReplicaActionResult result = new BatchReplicaActionResult();
        discardManualInterventionReplicas(result, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false);
        discardManualInterventionReplicas(result, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false);
        discardManualInterventionReplicas(result, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false);
        discardManualInterventionReplicas(result, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true);
        discardManualInterventionReplicas(result, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true);
        return result;
    }

    private BatchReplicaActionResult retryReplicasByCategory(P2PSyncStateStore store, Set<String> categories) {
        BatchReplicaActionResult result = new BatchReplicaActionResult();
        retryReplicasByCategory(result, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false, categories);
        retryReplicasByCategory(result, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false, categories);
        retryReplicasByCategory(result, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false, categories);
        retryReplicasByCategory(result, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true, categories);
        retryReplicasByCategory(result, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true, categories);
        return result;
    }

    private void retryReplicasByCategory(BatchReplicaActionResult result, P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir, Set<String> categories) {
        batchReplicasByCategory(result, store, set, type, dir, categories, true);
    }

    private BatchReplicaActionResult discardReplicasByCategory(P2PSyncStateStore store, Set<String> categories) {
        BatchReplicaActionResult result = new BatchReplicaActionResult();
        discardReplicasByCategory(result, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false, categories);
        discardReplicasByCategory(result, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false, categories);
        discardReplicasByCategory(result, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false, categories);
        discardReplicasByCategory(result, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true, categories);
        discardReplicasByCategory(result, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true, categories);
        return result;
    }

    private void discardReplicasByCategory(BatchReplicaActionResult result, P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir, Set<String> categories) {
        batchReplicasByCategory(result, store, set, type, dir, categories, false);
    }

    private void batchReplicasByCategory(BatchReplicaActionResult result, P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir, Set<String> categories, boolean retry) {
        List<Long> fileIds = new ArrayList<Long>();
        for (Long o : set) {
            fileIds.add(o);
        }
        for (Long fileIdRef : fileIds) {
            long fileId = fileIdRef.longValue();
            List<String> labels = replicaLabelsForCategories(store, type, dir, fileId, categories);
            if (labels.isEmpty()) {
                continue;
            }
            int updated = retry
                ? store.retryFailedReplicas(type, dir, fileId, labels)
                : store.discardFailedReplicas(type, dir, fileId, labels);
            if (updated > 0) {
                result.touchedFileCount++;
                result.touchedReplicaCount += updated;
            }
        }
    }

    private void discardManualInterventionReplicas(BatchReplicaActionResult result, P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir) {
        List<Long> fileIds = new ArrayList<Long>();
        for (Long o : set) {
            fileIds.add(o);
        }
        for (Long fileIdRef : fileIds) {
            long fileId = fileIdRef.longValue();
            List<String> labels = manualInterventionReplicaLabels(store, type, dir, fileId);
            if (labels.isEmpty()) {
                continue;
            }
            int updated = store.discardFailedReplicas(type, dir, fileId, labels);
            if (updated > 0) {
                result.touchedFileCount++;
                result.touchedReplicaCount += updated;
            }
        }
    }

    private List<String> autoRecoverableReplicaLabels(P2PSyncStateStore store, FileSyncEventType type, boolean dir, long fileId) {
        int retryCount = store.getRetryCount(type, dir, fileId);
        String reason = store.getFailedReason(type, dir, fileId);
        boolean autoRetryable = isRetryable(retryCount);
        boolean manualReason = "write_conflict".equals(reason) || "retry_limit_exceeded".equals(reason);
        List<String> labels = new ArrayList<String>();
        for (P2PSyncStateStore.ReplicaState replicaState : store.getReplicaStates(type, dir, fileId)) {
            String status = replicaState.getStatus();
            if (!isReplicaActionable(status)) {
                continue;
            }
            if (isReplicaScheduledStatus(status)) {
                if (!manualReason && autoRetryable) {
                    labels.add(replicaState.getLabel());
                }
                continue;
            }
            if (!manualReason && autoRetryable) {
                labels.add(replicaState.getLabel());
            }
        }
        return labels;
    }

    private List<String> manualInterventionReplicaLabels(P2PSyncStateStore store, FileSyncEventType type, boolean dir, long fileId) {
        int retryCount = store.getRetryCount(type, dir, fileId);
        String reason = store.getFailedReason(type, dir, fileId);
        boolean autoRetryable = isRetryable(retryCount);
        boolean manualReason = "write_conflict".equals(reason) || "retry_limit_exceeded".equals(reason);
        List<String> labels = new ArrayList<String>();
        for (P2PSyncStateStore.ReplicaState replicaState : store.getReplicaStates(type, dir, fileId)) {
            String status = replicaState.getStatus();
            if (!isReplicaActionable(status)) {
                continue;
            }
            if (isReplicaScheduledStatus(status)) {
                if (manualReason || !autoRetryable) {
                    labels.add(replicaState.getLabel());
                }
                continue;
            }
            if (manualReason || !autoRetryable) {
                labels.add(replicaState.getLabel());
            }
        }
        return labels;
    }

    private List<String> replicaLabelsForCategories(P2PSyncStateStore store, FileSyncEventType type, boolean dir, long fileId, Set<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return Collections.emptyList();
        }
        int retryCount = store.getRetryCount(type, dir, fileId);
        String reason = store.getFailedReason(type, dir, fileId);
        String reasonReplica = extractReplicaLabel(reason);
        String normalizedReason = normalizeReplicaReason(reason, retryCount);
        boolean autoRetryable = isRetryable(retryCount);
        boolean manualReason = "write_conflict".equals(normalizedReason) || "retry_limit_exceeded".equals(normalizedReason);
        List<String> labels = new ArrayList<String>();
        for (P2PSyncStateStore.ReplicaState replicaState : store.getReplicaStates(type, dir, fileId)) {
            String status = replicaState.getStatus();
            if (!isReplicaActionable(status)) {
                continue;
            }
            String replicaReason = resolveReplicaReason(replicaState.getLabel(), status, reasonReplica, normalizedReason, manualReason, autoRetryable);
            String category = replicaReasonCategory(replicaReason);
            if (categories.contains(category)) {
                labels.add(replicaState.getLabel());
            }
        }
        return labels;
    }

    private Map<String, Object> queuesToMap(P2PSyncStateStore store, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file_create", queue(store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.ACTIVE), FileSyncEventType.CREATE, false, limit));
        out.put("file_modify", queue(store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.ACTIVE), FileSyncEventType.MODIFY, false, limit));
        out.put("file_delete", queue(store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.ACTIVE), FileSyncEventType.DELETE, false, limit));
        out.put("dir_create", queue(store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.ACTIVE), FileSyncEventType.CREATE, true, limit));
        out.put("dir_delete", queue(store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.ACTIVE), FileSyncEventType.DELETE, true, limit));
        out.put("failed_file_create", failedQueue(store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false, limit));
        out.put("failed_file_modify", failedQueue(store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false, limit));
        out.put("failed_file_delete", failedQueue(store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false, limit));
        out.put("failed_dir_create", failedQueue(store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true, limit));
        out.put("failed_dir_delete", failedQueue(store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true, limit));
        return out;
    }

    private Map<String, Object> queue(P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", set.size());
        out.put("items", sampleItems(store, set, type, dir, limit, false));
        return out;
    }

    private Map<String, Object> failedQueue(P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", set.size());
        out.put("items", sampleItems(store, set, type, dir, limit, true));
        return out;
    }

    private List<Map<String, Object>> sampleItems(P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir, int limit, boolean includeReason) {
        List<Map<String, Object>> items = new ArrayList<>();
        int count = 0;
        for (Long o : set) {
            if (count >= limit) {
                break;
            }
            long fileId = o.longValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fileId", Long.toString(fileId));
            m.put("dir", dir);
            m.put("type", type.name());
            String path = store.getRelativePath(fileId);
            m.put("path", path == null ? "" : path);
            int retryCount = store.getRetryCount(type, dir, fileId);
            m.put("retryCount", Integer.valueOf(retryCount));
            m.put("remainingRetries", Integer.valueOf(remainingRetries(retryCount)));
            m.put("retryable", Boolean.valueOf(isRetryable(retryCount)));
            m.put("failedAtMillis", Long.valueOf(store.getFailedAtMillis(type, dir, fileId)));
            m.put("lastRetriedAtMillis", Long.valueOf(store.getLastRetriedAtMillis(type, dir, fileId)));
            if (includeReason) {
                String reason = store.getFailedReason(type, dir, fileId);
                m.put("reason", reason == null ? "" : reason);
                m.put("recoveryClass", recoveryClass(reason, retryCount));
                List<Map<String, Object>> replicaStates = replicaStatesToMap(store, type, dir, fileId);
                m.put("replicaStates", replicaStates);
                m.put("replicaSummary", replicaSummary(replicaStates));
                ReplicaRecoveryStats replicaStats = replicaRecoveryStats(store, type, dir, fileId, retryCount, reason);
                m.put("replicaRecoveryClass", replicaStats.recoveryClass);
                m.put("outstandingReplicaCount", Integer.valueOf(replicaStats.outstandingReplicaCount));
                m.put("autoRecoverableReplicaCount", Integer.valueOf(replicaStats.autoRecoverableReplicaCount));
                m.put("manualReplicaCount", Integer.valueOf(replicaStats.manualReplicaCount));
                Map<String, Integer> replicaReasonCounts = replicaReasonCounts(store, type, dir, fileId);
                m.put("replicaReasonSummary", reasonSummary(replicaReasonCounts));
                m.put("replicaReasonItems", reasonItems(replicaReasonCounts));
                Map<String, Integer> replicaCategoryCounts = replicaReasonCategoryCounts(replicaReasonCounts);
                m.put("replicaCategorySummary", reasonSummary(replicaCategoryCounts));
                m.put("replicaCategoryItems", reasonItems(replicaCategoryCounts));
            }
            items.add(m);
            count++;
        }
        return items;
    }

    private List<Map<String, Object>> replicaStatesToMap(P2PSyncStateStore store, FileSyncEventType type, boolean dir, long fileId) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (P2PSyncStateStore.ReplicaState replicaState : store.getReplicaStates(type, dir, fileId)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("label", replicaState.getLabel());
            item.put("status", replicaState.getStatus());
            items.add(item);
        }
        return items;
    }

    private String replicaSummary(List<Map<String, Object>> replicaStates) {
        if (replicaStates == null || replicaStates.isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        for (Map<String, Object> replicaState : replicaStates) {
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(String.valueOf(replicaState.get("label")));
            summary.append('=');
            summary.append(String.valueOf(replicaState.get("status")));
        }
        return summary.toString();
    }

    private boolean isReplicaActionable(String status) {
        if (status == null || status.isEmpty()) {
            return false;
        }
        return !P2PSyncStateStore.REPLICA_ACKED.equals(status)
            && !P2PSyncStateStore.REPLICA_DISCARDED.equals(status);
    }

    private boolean isReplicaScheduledStatus(String status) {
        return P2PSyncStateStore.REPLICA_TARGETED.equals(status)
            || P2PSyncStateStore.REPLICA_RETRY.equals(status);
    }

    private void addCount(Map<String, Integer> counts, String key, int delta) {
        if (delta <= 0) {
            return;
        }
        Integer current = counts.get(key);
        counts.put(key, Integer.valueOf(current == null ? delta : current.intValue() + delta));
    }

    private static String indexHtml() {
        return "<!doctype html>\n"
            + "<html>\n"
            + "<head>\n"
            + "  <meta charset=\"utf-8\"/>\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>\n"
            + "  <title>p2p-sync monitor</title>\n"
            + "  <style>\n"
            + "    body{font-family:system-ui,Arial; margin:16px;}\n"
            + "    table{border-collapse:collapse; width:100%; margin:12px 0;}\n"
            + "    th,td{border:1px solid #ddd; padding:6px 8px; font-size:12px;}\n"
            + "    th{background:#f6f6f6; text-align:left;}\n"
            + "    .page{display:flex; flex-direction:column; gap:20px;}\n"
            + "    .section{display:flex; flex-direction:column; gap:12px;}\n"
            + "    .section h3{margin:0; font-size:16px;}\n"
            + "    .row{display:flex; gap:16px; flex-wrap:wrap;}\n"
            + "    .card{flex:1 1 420px; border:1px solid #ddd; padding:12px;}\n"
            + "    .btn{padding:4px 8px; border:1px solid #666; background:#fff; cursor:pointer;}\n"
            + "  </style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <h2>p2p-sync 队列监控</h2>\n"
            + "  <div>\n"
            + "    <button class=\"btn\" onclick=\"reload()\">刷新</button>\n"
            + "    <button class=\"btn\" data-batch-action=\"retry-auto-recoverable-replicas\">批量重试可自动恢复副本</button>\n"
            + "    <button class=\"btn\" data-batch-action=\"discard-manual-replicas\">批量放弃人工介入副本</button>\n"
            + "    <button class=\"btn\" data-batch-action=\"retry-network-replicas\">批量重试 NETWORK 副本</button>\n"
            + "    <button class=\"btn\" data-batch-action=\"discard-conflict-retry-limit-replicas\">批量放弃 CONFLICT/RETRY_LIMIT 副本</button>\n"
            + "  </div>\n"
            + "  <div id=\"content\"></div>\n"
            + "  <script>\n"
            + "    async function reload(){\n"
            + "      const res = await fetch('/sync/api/queues?limit=200');\n"
            + "      const data = await res.json();\n"
            + "      if(!data.ok){document.getElementById('content').innerText = data.message || 'error';return;}\n"
            + "      render(data.queues, data.queueMatrix, data.healthSummary, data.failureTrend, data.recoverySuccessSummary, data.failureSummary, data.failureRecoverySummary, data.replicaRecoverySummary, data.replicaFailureSummary, data.replicaFailureCategorySummary, data.hotFailedItems, data.recentTimeline, data.uploads, data.uploadPolicy, data.retryPolicy, data.recentCompletedUploads, data.recentFailedUploads);\n"
            + "    }\n"
            + "    function esc(s){return (s||'').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;');}\n"
            + "    function escAttr(s){return esc(s).replaceAll('\"','&quot;').replaceAll(\"'\",'&#39;');}\n"
            + "    function renderQueue(title, q){\n"
            + "      let html = '<div class=\"card\"><h3>'+esc(title)+' (size='+q.size+')</h3>';\n"
            + "      html += '<table><tr><th>fileId</th><th>dir</th><th>type</th><th>path</th><th>retryCount</th><th>remainingRetries</th><th>retryable</th><th>recoveryClass</th><th>replicaRecoveryClass</th><th>replicas</th><th>replicaCategories</th><th>replicaReasons</th><th>outstandingReplicas</th><th>failedAtMillis</th><th>lastRetriedAtMillis</th><th>reason</th><th>action</th></tr>';\n"
            + "      for(const it of q.items){\n"
            + "        const reason = it.reason ? esc(it.reason) : '';\n"
            + "        const retryable = !!it.retryable;\n"
            + "        const retryState = retryable ? 'yes' : 'capped';\n"
            + "        let action = '';\n"
            + "        if(reason){\n"
            + "          action = '<button class=\"btn\" data-action=\"retry\" data-file-id=\"'+escAttr(it.fileId)+'\" data-dir=\"'+it.dir+'\" data-type=\"'+escAttr(it.type)+'\">重试(覆盖同步)</button> ' +\n"
            + "                   '<button class=\"btn\" data-action=\"discard\" data-file-id=\"'+escAttr(it.fileId)+'\" data-dir=\"'+it.dir+'\" data-type=\"'+escAttr(it.type)+'\">放弃</button>';\n"
            + "          if(it.replicaStates){\n"
            + "            for(const replica of it.replicaStates){\n"
            + "              if(replica && isReplicaActionable(replica.status)){\n"
            + "                action += '<br/><button class=\"btn\" data-action=\"retry\" data-file-id=\"'+escAttr(it.fileId)+'\" data-dir=\"'+it.dir+'\" data-type=\"'+escAttr(it.type)+'\" data-replica=\"'+escAttr(replica.label)+'\">重试副本:'+esc(replica.label)+'</button> ';\n"
            + "                action += '<button class=\"btn\" data-action=\"discard\" data-file-id=\"'+escAttr(it.fileId)+'\" data-dir=\"'+it.dir+'\" data-type=\"'+escAttr(it.type)+'\" data-replica=\"'+escAttr(replica.label)+'\">放弃副本:'+esc(replica.label)+'</button>';\n"
            + "              }\n"
            + "            }\n"
            + "          }\n"
            + "        }\n"
            + "        const outstandingReplicas = (it.outstandingReplicaCount || 0) + ' (auto=' + (it.autoRecoverableReplicaCount || 0) + ', manual=' + (it.manualReplicaCount || 0) + ')';\n"
            + "        html += '<tr><td>'+it.fileId+'</td><td>'+it.dir+'</td><td>'+esc(it.type)+'</td><td>'+esc(it.path)+'</td><td>'+it.retryCount+'</td><td>'+it.remainingRetries+'</td><td>'+retryState+'</td><td>'+esc(it.recoveryClass)+'</td><td>'+esc(it.replicaRecoveryClass || '')+'</td><td>'+esc(it.replicaSummary || '')+'</td><td>'+esc(it.replicaCategorySummary || '')+'</td><td>'+esc(it.replicaReasonSummary || '')+'</td><td>'+esc(outstandingReplicas)+'</td><td>'+it.failedAtMillis+'</td><td>'+it.lastRetriedAtMillis+'</td><td>'+reason+'</td><td>'+action+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderUploads(u){\n"
            + "      let html = '<div class=\"card\"><h3>上传中 (size='+u.size+')</h3>';\n"
            + "      html += '<table><tr><th>path</th><th>phase</th><th>size</th><th>segmented</th><th>progress</th></tr>';\n"
            + "      for(const it of u.items){\n"
            + "        const progress = it.totalSegments > 0 ? (it.uploadedSegments + '/' + it.totalSegments) : '-';\n"
            + "        html += '<tr><td>'+esc(it.path)+'</td><td>'+esc(it.phase)+'</td><td>'+it.fileSize+'</td><td>'+it.segmented+'</td><td>'+progress+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderUploadHistory(title, u){\n"
            + "      let html = '<div class=\"card\"><h3>'+esc(title)+' (size='+u.size+')</h3>';\n"
            + "      html += '<table><tr><th>path</th><th>phase</th><th>size</th><th>progress</th><th>message</th></tr>';\n"
            + "      for(const it of u.items){\n"
            + "        const progress = it.totalSegments > 0 ? (it.uploadedSegments + '/' + it.totalSegments) : '-';\n"
            + "        html += '<tr><td>'+esc(it.path)+'</td><td>'+esc(it.phase)+'</td><td>'+it.fileSize+'</td><td>'+progress+'</td><td>'+esc(it.message)+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderFailureTrend(t){\n"
            + "      let html = '<div class=\"card\"><h3>失败趋势</h3>';\n"
            + "      html += '<table><tr><th>recentFailedCount</th><th>failedLast5MinutesCount</th><th>failedLast60MinutesCount</th><th>outstandingFailedCount</th><th>latestFailedAtMillis</th></tr>';\n"
            + "      html += '<tr><td>'+t.recentFailedCount+'</td><td>'+t.failedLast5MinutesCount+'</td><td>'+t.failedLast60MinutesCount+'</td><td>'+t.outstandingFailedCount+'</td><td>'+t.latestFailedAtMillis+'</td></tr>';\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderRecoverySuccessSummary(s){\n"
            + "      let html = '<div class=\"card\"><h3>恢复成功率</h3>';\n"
            + "      html += '<table><tr><th>completedCount</th><th>failedCount</th><th>totalCount</th><th>successRatePercent</th><th>avgCompletedDurationMillis</th><th>avgFailedDurationMillis</th><th>lastCompletedAtMillis</th><th>lastFailedAtMillis</th></tr>';\n"
            + "      html += '<tr><td>'+s.completedCount+'</td><td>'+s.failedCount+'</td><td>'+s.totalCount+'</td><td>'+s.successRatePercent+'</td><td>'+s.avgCompletedDurationMillis+'</td><td>'+s.avgFailedDurationMillis+'</td><td>'+s.lastCompletedAtMillis+'</td><td>'+s.lastFailedAtMillis+'</td></tr>';\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderTimeline(t){\n"
            + "      let html = '<div class=\"card\"><h3>最近操作时间线 (size='+t.size+')</h3>';\n"
            + "      html += '<table><tr><th>path</th><th>phase</th><th>updatedAtMillis</th><th>message</th></tr>';\n"
            + "      for(const it of t.items){\n"
            + "        html += '<tr><td>'+esc(it.path)+'</td><td>'+esc(it.phase)+'</td><td>'+it.updatedAtMillis+'</td><td>'+esc(it.message)+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderSection(title, body){\n"
            + "      return '<section class=\"section\"><h3>'+esc(title)+'</h3><div class=\"row\">'+body+'</div></section>';\n"
            + "    }\n"
            + "    function renderQueueMatrix(m){\n"
            + "      let html = '<div class=\"card\"><h3>队列类型矩阵 (size='+m.size+')</h3>';\n"
            + "      html += '<table><tr><th>label</th><th>activeCount</th><th>failedCount</th></tr>';\n"
            + "      for(const it of m.items){\n"
            + "        html += '<tr><td>'+esc(it.label)+'</td><td>'+it.activeCount+'</td><td>'+it.failedCount+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderFailureRecoverySummary(s){\n"
            + "      let html = '<div class=\"card\"><h3>恢复分级汇总 (size='+s.size+', total='+s.totalFailedItems+')</h3>';\n"
            + "      html += '<table><tr><th>recoveryClass</th><th>count</th></tr>';\n"
            + "      for(const it of s.items){\n"
            + "        html += '<tr><td>'+esc(it.recoveryClass)+'</td><td>'+it.count+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderReplicaRecoverySummary(s){\n"
            + "      let html = '<div class=\"card\"><h3>副本恢复分级汇总 (size='+s.size+', outstanding='+s.totalOutstandingReplicas+')</h3>';\n"
            + "      html += '<table><tr><th>recoveryClass</th><th>count</th></tr>';\n"
            + "      for(const it of s.items){\n"
            + "        html += '<tr><td>'+esc(it.recoveryClass)+'</td><td>'+it.count+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderReplicaFailureSummary(s){\n"
            + "      let html = '<div class=\"card\"><h3>副本失败原因汇总 (size='+s.size+', outstanding='+s.totalOutstandingReplicas+')</h3>';\n"
            + "      html += '<table><tr><th>reason</th><th>count</th></tr>';\n"
            + "      for(const it of s.items){\n"
            + "        html += '<tr><td>'+esc(it.reason)+'</td><td>'+it.count+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderReplicaFailureCategorySummary(s){\n"
            + "      let html = '<div class=\"card\"><h3>副本失败类别汇总 (size='+s.size+', outstanding='+s.totalOutstandingReplicas+')</h3>';\n"
            + "      html += '<table><tr><th>category</th><th>count</th><th>recommendedAction</th><th>operatorHint</th><th>action</th></tr>';\n"
            + "      for(const it of s.items){\n"
            + "        html += '<tr><td>'+esc(it.reason)+'</td><td>'+it.count+'</td><td>'+esc(it.recommendedAction || '')+'</td><td>'+esc(it.operatorHint || '')+'</td><td>'+renderReplicaCategoryActionButtons(it)+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderHotFailedItems(h){\n"
            + "      let html = '<div class=\"card\"><h3>热点失败项 (size='+h.size+')</h3>';\n"
            + "      html += '<table><tr><th>path</th><th>type</th><th>retryCount</th><th>remainingRetries</th><th>retryable</th><th>recoveryClass</th><th>replicaRecoveryClass</th><th>replicas</th><th>replicaCategories</th><th>replicaReasons</th><th>outstandingReplicas</th><th>failedAtMillis</th><th>reason</th><th>recommendedAction</th><th>operatorHint</th><th>action</th></tr>';\n"
            + "      for(const it of h.items){\n"
            + "        const outstandingReplicas = (it.outstandingReplicaCount || 0) + ' (auto=' + (it.autoRecoverableReplicaCount || 0) + ', manual=' + (it.manualReplicaCount || 0) + ')';\n"
            + "        html += '<tr><td>'+esc(it.path)+'</td><td>'+esc(it.type)+'</td><td>'+it.retryCount+'</td><td>'+it.remainingRetries+'</td><td>'+(it.retryable ? 'yes' : 'capped')+'</td><td>'+esc(it.recoveryClass)+'</td><td>'+esc(it.replicaRecoveryClass || '')+'</td><td>'+esc(it.replicaSummary || '')+'</td><td>'+esc(it.replicaCategorySummary || '')+'</td><td>'+esc(it.replicaReasonSummary || '')+'</td><td>'+esc(outstandingReplicas)+'</td><td>'+it.failedAtMillis+'</td><td>'+esc(it.reason)+'</td><td>'+esc(it.recommendedAction || '')+'</td><td>'+esc(it.operatorHint || '')+'</td><td>'+renderHotFailedItemActionButtons(it)+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderHealthSummary(h){\n"
            + "      let html = '<div class=\"card\"><h3>队列健康概览</h3>';\n"
            + "      html += '<table><tr><th>activeCount</th><th>failedCount</th><th>uploadingCount</th><th>oldestFailedAtMillis</th><th>maxRetryCount</th></tr>';\n"
            + "      html += '<tr><td>'+h.activeCount+'</td><td>'+h.failedCount+'</td><td>'+h.uploadingCount+'</td><td>'+h.oldestFailedAtMillis+'</td><td>'+h.maxRetryCount+'</td></tr>';\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderFailureSummary(s){\n"
            + "      let html = '<div class=\"card\"><h3>失败原因汇总 (size='+s.size+', total='+s.totalFailedItems+')</h3>';\n"
            + "      html += '<table><tr><th>reason</th><th>count</th><th>recommendedAction</th><th>operatorHint</th></tr>';\n"
            + "      for(const it of s.items){\n"
            + "        html += '<tr><td>'+esc(it.reason)+'</td><td>'+it.count+'</td><td>'+esc(it.recommendedAction || '')+'</td><td>'+esc(it.operatorHint || '')+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderRetryPolicy(r){\n"
            + "      let html = '<div class=\"card\"><h3>重试策略</h3>';\n"
            + "      html += '<table><tr><th>autoRetryMode</th><th>maxRetryCount</th><th>retryBackoffMillis</th><th>manualRetryUnrestricted</th></tr>';\n"
            + "      html += '<tr><td>'+esc(r.autoRetryMode)+'</td><td>'+r.maxRetryCount+'</td><td>'+r.retryBackoffMillis+'</td><td>'+r.manualRetryUnrestricted+'</td></tr>';\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderUploadPolicy(p){\n"
            + "      let html = '<div class=\"card\"><h3>上传策略</h3>';\n"
            + "      html += '<table><tr><th>mode</th><th>uploadBlockSizeBytes</th><th>resumeSupported</th><th>historyRetention</th></tr>';\n"
            + "      html += '<tr><td>'+esc(p.mode)+'</td><td>'+p.uploadBlockSizeBytes+'</td><td>'+p.resumeSupported+'</td><td>'+esc(p.historyRetention)+'</td></tr>';\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderReplicaCategoryActionButtons(it){\n"
            + "      const category = it.reason || '';\n"
            + "      const action = it.recommendedAction || '';\n"
            + "      if(action === 'RETRY_NETWORK_REPLICAS' || action === 'VERIFY_STATE_THEN_RETRY'){\n"
            + "        return '<button class=\"btn\" data-category-action=\"retry\" data-category=\"'+escAttr(category)+'\">建议重试</button>';\n"
            + "      }\n"
            + "      if(action === 'MANUAL_RETRY_OR_DISCARD_REPLICAS'){\n"
            + "        return '<button class=\"btn\" data-category-action=\"retry\" data-category=\"'+escAttr(category)+'\">建议重试</button> ' +\n"
            + "               '<button class=\"btn\" data-category-action=\"discard\" data-category=\"'+escAttr(category)+'\">建议放弃</button>';\n"
            + "      }\n"
            + "      return '';\n"
            + "    }\n"
            + "    function renderHotFailedItemActionButtons(it){\n"
            + "      const action = it.recommendedAction || '';\n"
            + "      const baseRetry = '<button class=\"btn\" data-action=\"retry\" data-file-id=\"'+escAttr(it.fileId)+'\" data-dir=\"'+it.dir+'\" data-type=\"'+escAttr(it.type)+'\">建议重试</button>';\n"
            + "      const baseDiscard = '<button class=\"btn\" data-action=\"discard\" data-file-id=\"'+escAttr(it.fileId)+'\" data-dir=\"'+it.dir+'\" data-type=\"'+escAttr(it.type)+'\">建议放弃</button>';\n"
            + "      if(action === 'RETRY_FAILED_ITEM' || action === 'VERIFY_STATE_THEN_RETRY' || action === 'RESTORE_CONNECTIVITY_AND_RETRY'){\n"
            + "        return baseRetry;\n"
            + "      }\n"
            + "      if(action === 'MANUAL_RETRY_OR_DISCARD' || action === 'MANUAL_RETRY_OR_DISCARD_REPLICAS'){\n"
            + "        return baseRetry + ' ' + baseDiscard;\n"
            + "      }\n"
            + "      return '';\n"
            + "    }\n"
            + "    function isReplicaActionable(status){\n"
            + "      return !!status && status !== 'ACKED' && status !== 'DISCARDED';\n"
            + "    }\n"
            + "    async function retryIt(fileId, dir, type){\n"
            + "      let url = '/sync/api/failed/retry?fileId='+fileId+'&dir='+dir+'&type='+encodeURIComponent(type);\n"
            + "      if(arguments.length > 3 && arguments[3]){url += '&replica='+encodeURIComponent(arguments[3]);}\n"
            + "      await fetch(url, {method:'POST'});\n"
            + "      await reload();\n"
            + "    }\n"
            + "    async function discardIt(fileId, dir, type){\n"
            + "      let url = '/sync/api/failed/discard?fileId='+fileId+'&dir='+dir+'&type='+encodeURIComponent(type);\n"
            + "      if(arguments.length > 3 && arguments[3]){url += '&replica='+encodeURIComponent(arguments[3]);}\n"
            + "      await fetch(url, {method:'POST'});\n"
            + "      await reload();\n"
            + "    }\n"
            + "    async function retryAutoRecoverableReplicas(){\n"
            + "      await fetch('/sync/api/failed/retry-auto-recoverable-replicas', {method:'POST'});\n"
            + "      await reload();\n"
            + "    }\n"
            + "    async function discardManualReplicas(){\n"
            + "      await fetch('/sync/api/failed/discard-manual-replicas', {method:'POST'});\n"
            + "      await reload();\n"
            + "    }\n"
            + "    async function retryReplicasByCategory(category){\n"
            + "      await fetch('/sync/api/failed/retry-replicas-by-category?category='+encodeURIComponent(category), {method:'POST'});\n"
            + "      await reload();\n"
            + "    }\n"
            + "    async function discardReplicasByCategory(category){\n"
            + "      await fetch('/sync/api/failed/discard-replicas-by-category?category='+encodeURIComponent(category), {method:'POST'});\n"
            + "      await reload();\n"
            + "    }\n"
            + "    function render(queues, queueMatrix, healthSummary, failureTrend, recoverySuccessSummary, failureSummary, failureRecoverySummary, replicaRecoverySummary, replicaFailureSummary, replicaFailureCategorySummary, hotFailedItems, recentTimeline, uploads, uploadPolicy, retryPolicy, recentCompletedUploads, recentFailedUploads){\n"
            + "      const keys = [\n"
            + "        ['新增(文件)', 'file_create'],\n"
            + "        ['修改(文件)', 'file_modify'],\n"
            + "        ['删除(文件)', 'file_delete'],\n"
            + "        ['新增(目录)', 'dir_create'],\n"
            + "        ['删除(目录)', 'dir_delete'],\n"
            + "        ['失败-新增(文件)', 'failed_file_create'],\n"
            + "        ['失败-修改(文件)', 'failed_file_modify'],\n"
            + "        ['失败-删除(文件)', 'failed_file_delete'],\n"
            + "        ['失败-新增(目录)', 'failed_dir_create'],\n"
            + "        ['失败-删除(目录)', 'failed_dir_delete'],\n"
            + "      ];\n"
            + "      let overview = '';\n"
            + "      overview += renderQueueMatrix(queueMatrix || {size:0, items:[]});\n"
            + "      overview += renderHealthSummary(healthSummary || {activeCount:0, failedCount:0, uploadingCount:0, oldestFailedAtMillis:0, maxRetryCount:0});\n"
            + "      overview += renderFailureTrend(failureTrend || {recentFailedCount:0, failedLast5MinutesCount:0, failedLast60MinutesCount:0, outstandingFailedCount:0, latestFailedAtMillis:0});\n"
            + "      overview += renderRecoverySuccessSummary(recoverySuccessSummary || {completedCount:0, failedCount:0, totalCount:0, successRatePercent:0, avgCompletedDurationMillis:0, avgFailedDurationMillis:0, lastCompletedAtMillis:0, lastFailedAtMillis:0});\n"
            + "      overview += renderUploadPolicy(uploadPolicy || {mode:'AUTO_SEGMENT_RESUMABLE', uploadBlockSizeBytes:0, resumeSupported:true, historyRetention:'memory_recent'});\n"
            + "      overview += renderRetryPolicy(retryPolicy || {autoRetryMode:'LIMITED_WITH_BACKOFF', maxRetryCount:0, retryBackoffMillis:0, manualRetryUnrestricted:true});\n"
            + "      let failed = '';\n"
            + "      failed += renderFailureSummary(failureSummary || {size:0, totalFailedItems:0, items:[]});\n"
            + "      failed += renderFailureRecoverySummary(failureRecoverySummary || {size:0, totalFailedItems:0, items:[]});\n"
            + "      failed += renderReplicaRecoverySummary(replicaRecoverySummary || {size:0, totalOutstandingReplicas:0, items:[]});\n"
            + "      failed += renderReplicaFailureSummary(replicaFailureSummary || {size:0, totalOutstandingReplicas:0, items:[]});\n"
            + "      failed += renderReplicaFailureCategorySummary(replicaFailureCategorySummary || {size:0, totalOutstandingReplicas:0, items:[]});\n"
            + "      failed += renderHotFailedItems(hotFailedItems || {size:0, items:[]});\n"
            + "      failed += renderUploadHistory('最近失败上传', recentFailedUploads || {size:0, items:[]});\n"
            + "      let upload = '';\n"
            + "      upload += renderUploads(uploads || {size:0, items:[]});\n"
            + "      upload += renderUploadHistory('最近完成上传', recentCompletedUploads || {size:0, items:[]});\n"
            + "      upload += renderTimeline(recentTimeline || {size:0, items:[]});\n"
            + "      let queueDetails = '';\n"
            + "      for(const [title,key] of keys){\n"
            + "        queueDetails += renderQueue(title, queues[key]);\n"
            + "      }\n"
            + "      let html = '<div class=\"page\">';\n"
            + "      html += renderSection('总览区', overview);\n"
            + "      html += renderSection('失败区', failed);\n"
            + "      html += renderSection('上传区', upload);\n"
            + "      html += renderSection('队列明细区', queueDetails);\n"
            + "      html += '</div>';\n"
            + "      document.getElementById('content').innerHTML = html;\n"
            + "    }\n"
            + "    document.addEventListener('click', async function(e){\n"
            + "      const batchBtn = e.target.closest('button[data-batch-action]');\n"
            + "      if(batchBtn){\n"
            + "        if(batchBtn.getAttribute('data-batch-action') === 'retry-auto-recoverable-replicas'){\n"
            + "          await retryAutoRecoverableReplicas();\n"
            + "        } else if(batchBtn.getAttribute('data-batch-action') === 'discard-manual-replicas'){\n"
            + "          await discardManualReplicas();\n"
            + "        } else if(batchBtn.getAttribute('data-batch-action') === 'retry-network-replicas'){\n"
            + "          await retryReplicasByCategory('NETWORK');\n"
            + "        } else if(batchBtn.getAttribute('data-batch-action') === 'discard-conflict-retry-limit-replicas'){\n"
            + "          await discardReplicasByCategory('CONFLICT,RETRY_LIMIT');\n"
            + "        }\n"
            + "        return;\n"
            + "      }\n"
            + "      const categoryBtn = e.target.closest('button[data-category-action]');\n"
            + "      if(categoryBtn){\n"
            + "        const category = categoryBtn.getAttribute('data-category');\n"
            + "        if(categoryBtn.getAttribute('data-category-action') === 'retry'){\n"
            + "          await retryReplicasByCategory(category);\n"
            + "        } else if(categoryBtn.getAttribute('data-category-action') === 'discard'){\n"
            + "          await discardReplicasByCategory(category);\n"
            + "        }\n"
            + "        return;\n"
            + "      }\n"
            + "      const btn = e.target.closest('button[data-action]');\n"
            + "      if(!btn){return;}\n"
            + "      const fileId = btn.getAttribute('data-file-id');\n"
            + "      const dir = btn.getAttribute('data-dir');\n"
            + "      const type = btn.getAttribute('data-type');\n"
            + "      const replica = btn.getAttribute('data-replica');\n"
            + "      if(btn.getAttribute('data-action') === 'retry'){\n"
            + "        await retryIt(fileId, dir, type, replica);\n"
            + "      } else if(btn.getAttribute('data-action') === 'discard'){\n"
            + "        await discardIt(fileId, dir, type, replica);\n"
            + "      }\n"
            + "    });\n"
            + "    reload();\n"
            + "  </script>\n"
            + "</body>\n"
            + "</html>\n";
    }

    private static String param(URI uri, String key) {
        String q = uri.getRawQuery();
        if (q == null || q.trim().isEmpty()) {
            return null;
        }
        for (String part : q.split("&")) {
            int idx = part.indexOf('=');
            if (idx <= 0) continue;
            String k = part.substring(0, idx);
            if (!k.equals(key)) continue;
            String v = part.substring(idx + 1);
            return decode(v);
        }
        return null;
    }

    private static int parseIntParam(URI uri, String key, int def) {
        String v = param(uri, key);
        if (v == null) return def;
        return (int) parseLong(v, def);
    }

    private static long parseLong(String v, long def) {
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static FileSyncEventType parseType(String s) {
        try {
            return FileSyncEventType.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String decode(String v) {
        if (v == null) return null;
        try {
            return URLDecoder.decode(v, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return v;
        }
    }

    private static String toJson(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean) return ((Boolean) v).booleanValue() ? "true" : "false";
        if (v instanceof Number) return v.toString();
        if (v instanceof String) return quote((String) v);
        if (v instanceof Map<?, ?>) {
            Map<?, ?> m = (Map<?, ?>) v;
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(String.valueOf(e.getKey())));
                sb.append(':');
                sb.append(toJson(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (v instanceof List<?>) {
            List<?> list = (List<?>) v;
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;
            for (Object it : list) {
                if (!first) sb.append(',');
                first = false;
                sb.append(toJson(it));
            }
            sb.append(']');
            return sb.toString();
        }
        return quote(String.valueOf(v));
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static final class HealthStats {
        private int failedCount;
        private int maxRetryCount;
        private long oldestFailedAtMillis;
    }

    private static final class BatchReplicaActionResult {
        private int touchedFileCount;
        private int touchedReplicaCount;
    }

    private static final class ReplicaRecoveryStats {
        private final int outstandingReplicaCount;
        private final int autoRecoverableReplicaCount;
        private final int manualReplicaCount;
        private final String recoveryClass;

        private ReplicaRecoveryStats(int outstandingReplicaCount, int autoRecoverableReplicaCount, int manualReplicaCount) {
            this.outstandingReplicaCount = outstandingReplicaCount;
            this.autoRecoverableReplicaCount = autoRecoverableReplicaCount;
            this.manualReplicaCount = manualReplicaCount;
            if (outstandingReplicaCount <= 0) {
                this.recoveryClass = "RESOLVED";
            } else if (manualReplicaCount > 0) {
                this.recoveryClass = "MANUAL_INTERVENTION";
            } else {
                this.recoveryClass = "AUTO_RECOVERABLE";
            }
        }
    }
}
