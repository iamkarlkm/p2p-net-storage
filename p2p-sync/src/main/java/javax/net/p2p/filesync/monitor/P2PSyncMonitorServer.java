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
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
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

    private static final int MAX_RECENT_OPERATOR_ACTIONS = 50;

    private final HttpServer server;
    private final P2PDirectorySyncService syncService;
    private final Deque<MonitorActionRecord> recentOperatorActions = new ConcurrentLinkedDeque<MonitorActionRecord>();

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
            long recordedAtMillis = System.currentTimeMillis();
            recordOperatorAction(singleActionName(retry, replica), ok, 1, ok ? 1 : 0, t, directory, fileId, replica, null,
                ok ? "manual_action_applied" : "not_found");
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("ok", Boolean.valueOf(ok));
            body.put("recordedAtMillis", Long.valueOf(recordedAtMillis));
            if (!ok) {
                body.put("message", "not found");
            }
            writeJson(exchange, 200, toJson(body));
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
            body.put("recordedAtMillis", Long.valueOf(result.recordedAtMillis));
            appendBatchReplicaSummary(body, result);
            recordOperatorAction("RETRY_AUTO_RECOVERABLE_REPLICAS", true, result.touchedFileCount, result.touchedReplicaCount,
                null, false, 0L, null, null, batchActionMessage("batch_retry_auto_recoverable", result), result);
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
            body.put("recordedAtMillis", Long.valueOf(result.recordedAtMillis));
            appendBatchReplicaSummary(body, result);
            recordOperatorAction("DISCARD_MANUAL_REPLICAS", true, result.touchedFileCount, result.touchedReplicaCount,
                null, false, 0L, null, null, batchActionMessage("batch_discard_manual", result), result);
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
            body.put("recordedAtMillis", Long.valueOf(result.recordedAtMillis));
            appendBatchReplicaSummary(body, result);
            recordOperatorAction(retry ? "RETRY_REPLICAS_BY_CATEGORY" : "DISCARD_REPLICAS_BY_CATEGORY",
                true,
                result.touchedFileCount,
                result.touchedReplicaCount,
                null,
                false,
                0L,
                null,
                categories,
                batchActionMessage("batch_category_action", result),
                result);
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
        root.put("recentOperatorActions", recentOperatorActionsToMap(limit));
        root.put("recentTimeline", recentTimelineToMap(store, limit));
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
        Map<String, Integer> counts = replicaFailureCategoryCounts(store);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("size", Integer.valueOf(counts.size()));
        out.put("totalOutstandingReplicas", Integer.valueOf(totalCount(counts)));
        out.put("summary", reasonSummary(counts));
        out.put("items", replicaCategoryItems(counts));
        return out;
    }

    private Map<String, Integer> replicaFailureCategoryCounts(P2PSyncStateStore store) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        collectReplicaFailureCategories(counts, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false);
        collectReplicaFailureCategories(counts, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false);
        collectReplicaFailureCategories(counts, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false);
        collectReplicaFailureCategories(counts, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true);
        collectReplicaFailureCategories(counts, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true);
        return counts;
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

    private Map<String, Object> recentTimelineToMap(P2PSyncStateStore store, int limit) {
        List<Map<String, Object>> timeline = new ArrayList<Map<String, Object>>();
        for (SyncUploadStatus upload : syncService.snapshotRecentCompletedUploads(limit)) {
            timeline.add(uploadToMap(upload));
        }
        for (SyncUploadStatus upload : syncService.snapshotRecentFailedUploads(limit)) {
            timeline.add(uploadToMap(upload));
        }
        int operatorCount = 0;
        for (MonitorActionRecord record : recentOperatorActions) {
            if (operatorCount >= limit) {
                break;
            }
            timeline.add(operatorActionTimelineItem(store, record));
            operatorCount++;
        }
        Collections.sort(timeline, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                long leftTime = ((Long) left.get("updatedAtMillis")).longValue();
                long rightTime = ((Long) right.get("updatedAtMillis")).longValue();
                return leftTime < rightTime ? 1 : (leftTime == rightTime ? 0 : -1);
            }
        });
        if (timeline.size() > limit) {
            timeline = new ArrayList<Map<String, Object>>(timeline.subList(0, limit));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("size", Integer.valueOf(timeline.size()));
        out.put("items", timeline);
        return out;
    }

    private Map<String, Object> recentOperatorActionsToMap(int limit) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int count = 0;
        for (MonitorActionRecord record : recentOperatorActions) {
            if (count >= limit) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("action", record.action);
            item.put("success", Boolean.valueOf(record.success));
            item.put("touchedFileCount", Integer.valueOf(record.touchedFileCount));
            item.put("touchedReplicaCount", Integer.valueOf(record.touchedReplicaCount));
            item.put("type", record.type == null ? "" : record.type.name());
            item.put("dir", Boolean.valueOf(record.directory));
            item.put("fileId", record.fileId > 0L ? Long.toString(record.fileId) : "");
            item.put("replica", record.replica == null ? "" : record.replica);
            item.put("categories", record.categories == null ? Collections.emptyList() : new ArrayList<String>(record.categories));
            item.put("updatedAtMillis", Long.valueOf(record.updatedAtMillis));
            item.put("message", record.message == null ? "" : record.message);
            item.put("clearedFailedItemCount", Integer.valueOf(record.clearedFailedItemCount));
            item.put("clearedOutstandingReplicaCount", Integer.valueOf(record.clearedOutstandingReplicaCount));
            item.put("clearedReplicaCategorySummary", record.clearedReplicaCategorySummary == null ? "" : record.clearedReplicaCategorySummary);
            item.put("remainingFailedItemCount", Integer.valueOf(record.remainingFailedItemCount));
            item.put("remainingOutstandingReplicaCount", Integer.valueOf(record.remainingOutstandingReplicaCount));
            item.put("remainingReplicaCategorySummary", record.remainingReplicaCategorySummary == null ? "" : record.remainingReplicaCategorySummary);
            items.add(item);
            count++;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("size", Integer.valueOf(items.size()));
        out.put("items", items);
        return out;
    }

    private void appendBatchReplicaSummary(Map<String, Object> body, BatchReplicaActionResult result) {
        body.put("clearedFailedItemCount", Integer.valueOf(result.clearedFailedItemCount));
        body.put("clearedOutstandingReplicaCount", Integer.valueOf(result.clearedOutstandingReplicaCount));
        body.put("clearedReplicaCategorySummary", result.clearedReplicaCategorySummary == null ? "" : result.clearedReplicaCategorySummary);
        body.put("clearedReplicaCategoryItems", result.clearedReplicaCategoryItems == null
            ? Collections.emptyList()
            : result.clearedReplicaCategoryItems);
        body.put("clearedFailedItemsPreview", result.clearedFailedItemsPreview == null
            ? Collections.emptyList()
            : result.clearedFailedItemsPreview);
        body.put("remainingFailedItemCount", Integer.valueOf(result.remainingFailedItemCount));
        body.put("remainingOutstandingReplicaCount", Integer.valueOf(result.remainingOutstandingReplicaCount));
        body.put("remainingReplicaCategorySummary", result.remainingReplicaCategorySummary == null ? "" : result.remainingReplicaCategorySummary);
        body.put("remainingReplicaCategoryItems", result.remainingReplicaCategoryItems == null
            ? Collections.emptyList()
            : result.remainingReplicaCategoryItems);
        body.put("remainingFailedItemsPreview", result.remainingFailedItemsPreview == null
            ? Collections.emptyList()
            : result.remainingFailedItemsPreview);
    }

    private String batchActionMessage(String prefix, BatchReplicaActionResult result) {
        return prefix
            + " clearedFailedItems=" + result.clearedFailedItemCount
            + " clearedOutstandingReplicas=" + result.clearedOutstandingReplicaCount
            + " clearedCategories=" + (result.clearedReplicaCategorySummary == null ? "" : result.clearedReplicaCategorySummary)
            + " clearedPreviewPaths=" + (result.clearedFailedPathsSummary == null ? "" : result.clearedFailedPathsSummary)
            + " remainingFailedItems=" + result.remainingFailedItemCount
            + " remainingOutstandingReplicas=" + result.remainingOutstandingReplicaCount
            + " remainingCategories=" + (result.remainingReplicaCategorySummary == null ? "" : result.remainingReplicaCategorySummary)
            + " previewPaths=" + (result.remainingFailedPathsSummary == null ? "" : result.remainingFailedPathsSummary);
    }

    private Map<String, Object> operatorActionTimelineItem(P2PSyncStateStore store, MonitorActionRecord record) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("path", resolveOperatorActionPath(store, record));
        item.put("phase", "operator_action");
        item.put("updatedAtMillis", Long.valueOf(record.updatedAtMillis));
        item.put("message", operatorActionTimelineMessage(record));
        item.put("action", record.action);
        item.put("success", Boolean.valueOf(record.success));
        item.put("fileId", record.fileId > 0L ? Long.toString(record.fileId) : "");
        item.put("clearedFailedItemCount", Integer.valueOf(record.clearedFailedItemCount));
        item.put("clearedOutstandingReplicaCount", Integer.valueOf(record.clearedOutstandingReplicaCount));
        item.put("clearedReplicaCategorySummary", record.clearedReplicaCategorySummary == null ? "" : record.clearedReplicaCategorySummary);
        item.put("remainingFailedItemCount", Integer.valueOf(record.remainingFailedItemCount));
        item.put("remainingOutstandingReplicaCount", Integer.valueOf(record.remainingOutstandingReplicaCount));
        item.put("remainingReplicaCategorySummary", record.remainingReplicaCategorySummary == null ? "" : record.remainingReplicaCategorySummary);
        return item;
    }

    private String resolveOperatorActionPath(P2PSyncStateStore store, MonitorActionRecord record) {
        if (record.fileId > 0L) {
            String path = store.getRelativePath(record.fileId);
            if (path != null && !path.isBlank()) {
                return path;
            }
        }
        if (record.categories != null && !record.categories.isEmpty()) {
            return String.join(",", record.categories);
        }
        return record.action;
    }

    private String operatorActionTimelineMessage(MonitorActionRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append(record.action);
        sb.append(" success=").append(record.success);
        sb.append(" touchedFiles=").append(record.touchedFileCount);
        sb.append(" touchedReplicas=").append(record.touchedReplicaCount);
        if (record.replica != null && !record.replica.isBlank()) {
            sb.append(" replica=").append(record.replica);
        }
        if (record.categories != null && !record.categories.isEmpty()) {
            sb.append(" categories=").append(String.join(",", record.categories));
        }
        if (record.message != null && !record.message.isBlank()) {
            sb.append(" message=").append(record.message);
        }
        return sb.toString();
    }

    private void recordOperatorAction(String action, boolean success, int touchedFileCount, int touchedReplicaCount,
                                      FileSyncEventType type, boolean directory, long fileId, String replica,
                                      Set<String> categories, String message) {
        recordOperatorAction(action, success, touchedFileCount, touchedReplicaCount, type, directory, fileId, replica, categories, message, null);
    }

    private void recordOperatorAction(String action, boolean success, int touchedFileCount, int touchedReplicaCount,
                                      FileSyncEventType type, boolean directory, long fileId, String replica,
                                      Set<String> categories, String message, BatchReplicaActionResult result) {
        long now = System.currentTimeMillis();
        List<String> categoryList = categories == null || categories.isEmpty()
            ? Collections.<String>emptyList()
            : new ArrayList<String>(categories);
        recentOperatorActions.addFirst(new MonitorActionRecord(action, success, touchedFileCount, touchedReplicaCount,
            type, directory, fileId, replica, categoryList, now, message,
            result == null ? 0 : result.clearedFailedItemCount,
            result == null ? 0 : result.clearedOutstandingReplicaCount,
            result == null ? "" : result.clearedReplicaCategorySummary,
            result == null ? 0 : result.remainingFailedItemCount,
            result == null ? 0 : result.remainingOutstandingReplicaCount,
            result == null ? "" : result.remainingReplicaCategorySummary));
        while (recentOperatorActions.size() > MAX_RECENT_OPERATOR_ACTIONS) {
            recentOperatorActions.pollLast();
        }
    }

    private String singleActionName(boolean retry, String replica) {
        if (replica != null && !replica.isBlank()) {
            return retry ? "RETRY_REPLICA" : "DISCARD_REPLICA";
        }
        return retry ? "RETRY_FAILED_ITEM" : "DISCARD_FAILED_ITEM";
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
        BatchReplicaSnapshot before = snapshotBatchReplicaState(store);
        retryAutoRecoverableReplicas(result, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false);
        retryAutoRecoverableReplicas(result, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false);
        retryAutoRecoverableReplicas(result, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false);
        retryAutoRecoverableReplicas(result, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true);
        retryAutoRecoverableReplicas(result, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true);
        populateBatchReplicaActionSummary(result, store, before);
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
        BatchReplicaSnapshot before = snapshotBatchReplicaState(store);
        discardManualInterventionReplicas(result, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false);
        discardManualInterventionReplicas(result, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false);
        discardManualInterventionReplicas(result, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false);
        discardManualInterventionReplicas(result, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true);
        discardManualInterventionReplicas(result, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true);
        populateBatchReplicaActionSummary(result, store, before);
        return result;
    }

    private BatchReplicaActionResult retryReplicasByCategory(P2PSyncStateStore store, Set<String> categories) {
        BatchReplicaActionResult result = new BatchReplicaActionResult();
        BatchReplicaSnapshot before = snapshotBatchReplicaState(store);
        retryReplicasByCategory(result, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false, categories);
        retryReplicasByCategory(result, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false, categories);
        retryReplicasByCategory(result, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false, categories);
        retryReplicasByCategory(result, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true, categories);
        retryReplicasByCategory(result, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true, categories);
        populateBatchReplicaActionSummary(result, store, before);
        return result;
    }

    private void retryReplicasByCategory(BatchReplicaActionResult result, P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir, Set<String> categories) {
        batchReplicasByCategory(result, store, set, type, dir, categories, true);
    }

    private BatchReplicaActionResult discardReplicasByCategory(P2PSyncStateStore store, Set<String> categories) {
        BatchReplicaActionResult result = new BatchReplicaActionResult();
        BatchReplicaSnapshot before = snapshotBatchReplicaState(store);
        discardReplicasByCategory(result, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false, categories);
        discardReplicasByCategory(result, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false, categories);
        discardReplicasByCategory(result, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false, categories);
        discardReplicasByCategory(result, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true, categories);
        discardReplicasByCategory(result, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true, categories);
        populateBatchReplicaActionSummary(result, store, before);
        return result;
    }

    private void populateBatchReplicaActionSummary(BatchReplicaActionResult result, P2PSyncStateStore store, BatchReplicaSnapshot before) {
        Map<String, Integer> remainingCategoryCounts = replicaFailureCategoryCounts(store);
        result.remainingFailedItemCount = totalFailedCount(store);
        result.remainingOutstandingReplicaCount = totalCount(remainingCategoryCounts);
        result.remainingReplicaCategorySummary = reasonSummary(remainingCategoryCounts);
        result.remainingReplicaCategoryItems = replicaCategoryItems(remainingCategoryCounts);
        Map<String, Object> preview = hotFailedItemsToMap(store, 3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> previewItems = (List<Map<String, Object>>) preview.get("items");
        result.remainingFailedItemsPreview = previewItems == null ? Collections.<Map<String, Object>>emptyList() : previewItems;
        result.remainingFailedPathsSummary = previewPathSummary(result.remainingFailedItemsPreview);
        result.clearedFailedItemCount = Math.max(0, before.failedItemCount - result.remainingFailedItemCount);
        result.clearedOutstandingReplicaCount = Math.max(0, before.outstandingReplicaCount - result.remainingOutstandingReplicaCount);
        Map<String, Integer> clearedCategoryCounts = subtractCounts(before.categoryCounts, remainingCategoryCounts);
        result.clearedReplicaCategorySummary = reasonSummary(clearedCategoryCounts);
        result.clearedReplicaCategoryItems = replicaCategoryItems(clearedCategoryCounts);
        result.clearedFailedItemsPreview = subtractPreviewItems(before.previewItems, result.remainingFailedItemsPreview);
        result.clearedFailedPathsSummary = previewPathSummary(result.clearedFailedItemsPreview);
    }

    private String previewPathSummary(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : items) {
            String path = String.valueOf(item.get("path"));
            if (path == null || path.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(path);
        }
        return sb.toString();
    }

    private BatchReplicaSnapshot snapshotBatchReplicaState(P2PSyncStateStore store) {
        BatchReplicaSnapshot snapshot = new BatchReplicaSnapshot();
        snapshot.failedItemCount = totalFailedCount(store);
        snapshot.categoryCounts = replicaFailureCategoryCounts(store);
        snapshot.outstandingReplicaCount = totalCount(snapshot.categoryCounts);
        Map<String, Object> preview = hotFailedItemsToMap(store, 3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> previewItems = (List<Map<String, Object>>) preview.get("items");
        snapshot.previewItems = previewItems == null ? Collections.<Map<String, Object>>emptyList() : previewItems;
        return snapshot;
    }

    private Map<String, Integer> subtractCounts(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> diff = new LinkedHashMap<String, Integer>();
        for (Entry<String, Integer> entry : before.entrySet()) {
            int beforeValue = entry.getValue().intValue();
            int afterValue = after.containsKey(entry.getKey()) ? after.get(entry.getKey()).intValue() : 0;
            if (beforeValue > afterValue) {
                diff.put(entry.getKey(), Integer.valueOf(beforeValue - afterValue));
            }
        }
        return diff;
    }

    private List<Map<String, Object>> subtractPreviewItems(List<Map<String, Object>> before, List<Map<String, Object>> after) {
        Set<String> remainingKeys = new LinkedHashSet<String>();
        if (after != null) {
            for (Map<String, Object> item : after) {
                remainingKeys.add(previewItemKey(item));
            }
        }
        List<Map<String, Object>> cleared = new ArrayList<Map<String, Object>>();
        if (before == null) {
            return cleared;
        }
        for (Map<String, Object> item : before) {
            if (!remainingKeys.contains(previewItemKey(item))) {
                cleared.add(item);
            }
        }
        return cleared;
    }

    private String previewItemKey(Map<String, Object> item) {
        if (item == null) {
            return "";
        }
        return String.valueOf(item.get("fileId")) + "|" + String.valueOf(item.get("path")) + "|"
            + String.valueOf(item.get("type")) + "|" + String.valueOf(item.get("dir"));
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
            + "      render(data.queues, data.queueMatrix, data.healthSummary, data.failureTrend, data.recoverySuccessSummary, data.failureSummary, data.failureRecoverySummary, data.replicaRecoverySummary, data.replicaFailureSummary, data.replicaFailureCategorySummary, data.hotFailedItems, data.recentOperatorActions, data.recentTimeline, data.uploads, data.uploadPolicy, data.retryPolicy, data.recentCompletedUploads, data.recentFailedUploads);\n"
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
            + "    function formatDeltaSummary(it){\n"
            + "      return 'failedItems=' + (it.clearedFailedItemCount || 0)\n"
            + "        + ', outstandingReplicas=' + (it.clearedOutstandingReplicaCount || 0)\n"
            + "        + ', categories=' + (it.clearedReplicaCategorySummary || '');\n"
            + "    }\n"
            + "    function formatRemainingSummary(it){\n"
            + "      return 'failedItems=' + (it.remainingFailedItemCount || 0)\n"
            + "        + ', outstandingReplicas=' + (it.remainingOutstandingReplicaCount || 0)\n"
            + "        + ', categories=' + (it.remainingReplicaCategorySummary || '');\n"
            + "    }\n"
            + "    function renderTimeline(t){\n"
            + "      let html = '<div class=\"card\"><h3>最近操作时间线 (size='+t.size+')</h3>';\n"
            + "      html += '<table><tr><th>path</th><th>phase</th><th>cleared</th><th>remaining</th><th>updatedAtMillis</th><th>message</th></tr>';\n"
            + "      for(const it of t.items){\n"
            + "        html += '<tr><td>'+esc(it.path)+'</td><td>'+esc(it.phase)+'</td><td>'+esc(formatDeltaSummary(it))+'</td><td>'+esc(formatRemainingSummary(it))+'</td><td>'+it.updatedAtMillis+'</td><td>'+esc(it.message)+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderRecentOperatorActions(a){\n"
            + "      let html = '<div class=\"card\"><h3>最近运维动作 (size='+a.size+')</h3>';\n"
            + "      html += '<table><tr><th>action</th><th>success</th><th>type</th><th>dir</th><th>fileId</th><th>replica</th><th>categories</th><th>touchedFiles</th><th>touchedReplicas</th><th>cleared</th><th>remaining</th><th>updatedAtMillis</th><th>message</th></tr>';\n"
            + "      for(const it of a.items){\n"
            + "        const categories = Array.isArray(it.categories) ? it.categories.join(',') : '';\n"
            + "        html += '<tr><td>'+esc(it.action)+'</td><td>'+it.success+'</td><td>'+esc(it.type || '')+'</td><td>'+it.dir+'</td><td>'+esc(it.fileId || '')+'</td><td>'+esc(it.replica || '')+'</td><td>'+esc(categories)+'</td><td>'+it.touchedFileCount+'</td><td>'+it.touchedReplicaCount+'</td><td>'+esc(formatDeltaSummary(it))+'</td><td>'+esc(formatRemainingSummary(it))+'</td><td>'+it.updatedAtMillis+'</td><td>'+esc(it.message || '')+'</td></tr>';\n"
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
            + "    function renderBatchResultPreview(b){\n"
            + "      if(!b || !Array.isArray(b.remainingFailedItemsPreview) || b.remainingFailedItemsPreview.length === 0){\n"
            + "        return '';\n"
            + "      }\n"
            + "      let html = '<div class=\"card\"><h3>本次批量动作剩余失败预览</h3>';\n"
            + "      html += '<p>clearedFailedItems=' + (b.clearedFailedItemCount || 0) + ', clearedOutstandingReplicas=' + (b.clearedOutstandingReplicaCount || 0) + ', clearedCategories=' + esc(b.clearedReplicaCategorySummary || '') + '</p>';\n"
            + "      html += '<p>remainingFailedItems='+ (b.remainingFailedItemCount || 0) + ', remainingOutstandingReplicas=' + (b.remainingOutstandingReplicaCount || 0) + ', remainingCategories=' + esc(b.remainingReplicaCategorySummary || '') + '</p>';\n"
            + "      if(Array.isArray(b.clearedFailedItemsPreview) && b.clearedFailedItemsPreview.length > 0){\n"
            + "        html += '<p>clearedPreviewPaths=' + esc((b.clearedFailedItemsPreview || []).map(it => it.path || '').filter(Boolean).join(',')) + '</p>';\n"
            + "      }\n"
            + "      html += '<table><tr><th>path</th><th>type</th><th>retryCount</th><th>remainingRetries</th><th>retryable</th><th>recoveryClass</th><th>replicaRecoveryClass</th><th>replicas</th><th>replicaCategories</th><th>replicaReasons</th><th>outstandingReplicas</th><th>failedAtMillis</th><th>reason</th><th>recommendedAction</th><th>operatorHint</th><th>action</th></tr>';\n"
            + "      for(const it of b.remainingFailedItemsPreview){\n"
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
            + "      window.lastBatchResult = await fetch('/sync/api/failed/retry-auto-recoverable-replicas', {method:'POST'}).then(r => r.json());\n"
            + "      await reload();\n"
            + "    }\n"
            + "    async function discardManualReplicas(){\n"
            + "      window.lastBatchResult = await fetch('/sync/api/failed/discard-manual-replicas', {method:'POST'}).then(r => r.json());\n"
            + "      await reload();\n"
            + "    }\n"
            + "    async function retryReplicasByCategory(category){\n"
            + "      window.lastBatchResult = await fetch('/sync/api/failed/retry-replicas-by-category?category='+encodeURIComponent(category), {method:'POST'}).then(r => r.json());\n"
            + "      await reload();\n"
            + "    }\n"
            + "    async function discardReplicasByCategory(category){\n"
            + "      window.lastBatchResult = await fetch('/sync/api/failed/discard-replicas-by-category?category='+encodeURIComponent(category), {method:'POST'}).then(r => r.json());\n"
            + "      await reload();\n"
            + "    }\n"
            + "    function render(queues, queueMatrix, healthSummary, failureTrend, recoverySuccessSummary, failureSummary, failureRecoverySummary, replicaRecoverySummary, replicaFailureSummary, replicaFailureCategorySummary, hotFailedItems, recentOperatorActions, recentTimeline, uploads, uploadPolicy, retryPolicy, recentCompletedUploads, recentFailedUploads){\n"
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
            + "      failed += renderBatchResultPreview(window.lastBatchResult || null);\n"
            + "      failed += renderRecentOperatorActions(recentOperatorActions || {size:0, items:[]});\n"
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
        private long recordedAtMillis = System.currentTimeMillis();
        private int clearedFailedItemCount;
        private int clearedOutstandingReplicaCount;
        private String clearedReplicaCategorySummary = "";
        private List<Map<String, Object>> clearedReplicaCategoryItems = Collections.emptyList();
        private List<Map<String, Object>> clearedFailedItemsPreview = Collections.emptyList();
        private String clearedFailedPathsSummary = "";
        private int remainingFailedItemCount;
        private int remainingOutstandingReplicaCount;
        private String remainingReplicaCategorySummary = "";
        private List<Map<String, Object>> remainingReplicaCategoryItems = Collections.emptyList();
        private List<Map<String, Object>> remainingFailedItemsPreview = Collections.emptyList();
        private String remainingFailedPathsSummary = "";
    }

    private static final class BatchReplicaSnapshot {
        private int failedItemCount;
        private int outstandingReplicaCount;
        private Map<String, Integer> categoryCounts = Collections.emptyMap();
        private List<Map<String, Object>> previewItems = Collections.emptyList();
    }

    private static final class MonitorActionRecord {
        private final String action;
        private final boolean success;
        private final int touchedFileCount;
        private final int touchedReplicaCount;
        private final FileSyncEventType type;
        private final boolean directory;
        private final long fileId;
        private final String replica;
        private final List<String> categories;
        private final long updatedAtMillis;
        private final String message;
        private final int clearedFailedItemCount;
        private final int clearedOutstandingReplicaCount;
        private final String clearedReplicaCategorySummary;
        private final int remainingFailedItemCount;
        private final int remainingOutstandingReplicaCount;
        private final String remainingReplicaCategorySummary;

        private MonitorActionRecord(String action, boolean success, int touchedFileCount, int touchedReplicaCount,
                                    FileSyncEventType type, boolean directory, long fileId, String replica,
                                    List<String> categories, long updatedAtMillis, String message,
                                    int clearedFailedItemCount, int clearedOutstandingReplicaCount, String clearedReplicaCategorySummary,
                                    int remainingFailedItemCount, int remainingOutstandingReplicaCount, String remainingReplicaCategorySummary) {
            this.action = action;
            this.success = success;
            this.touchedFileCount = touchedFileCount;
            this.touchedReplicaCount = touchedReplicaCount;
            this.type = type;
            this.directory = directory;
            this.fileId = fileId;
            this.replica = replica;
            this.categories = categories;
            this.updatedAtMillis = updatedAtMillis;
            this.message = message;
            this.clearedFailedItemCount = clearedFailedItemCount;
            this.clearedOutstandingReplicaCount = clearedOutstandingReplicaCount;
            this.clearedReplicaCategorySummary = clearedReplicaCategorySummary;
            this.remainingFailedItemCount = remainingFailedItemCount;
            this.remainingOutstandingReplicaCount = remainingOutstandingReplicaCount;
            this.remainingReplicaCategorySummary = remainingReplicaCategorySummary;
        }
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
