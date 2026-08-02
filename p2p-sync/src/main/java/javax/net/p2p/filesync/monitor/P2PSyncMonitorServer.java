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
    private static final long SEGMENT_UPLOAD_STALL_THRESHOLD_MILLIS = 5000L;

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
        Map<String, Object> recentTimeline = recentTimelineToMap(store, limit);
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
        root.put("recentTimeline", recentTimeline);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recentTimelineItems = (List<Map<String, Object>>) recentTimeline.get("items");
        root.put("timelineRiskSummary", timelineRiskSummaryToMap(recentTimelineItems));
        root.put("resumedReplicaHotspots", resumedReplicaHotspotsToMap(limit));
        root.put("stalledUploads", stalledUploadsToMap(limit));
        root.put("stalledReplicaHotspots", stalledReplicaHotspotsToMap(limit));
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
        for (SyncUploadStatus upload : syncService.snapshotActiveUploads(limit)) {
            Map<String, Object> item = uploadToMap(upload);
            enrichTimelineRisk(item);
            timeline.add(item);
        }
        for (SyncUploadStatus upload : syncService.snapshotRecentCompletedUploads(limit)) {
            Map<String, Object> item = uploadToMap(upload);
            enrichTimelineRisk(item);
            timeline.add(item);
        }
        for (SyncUploadStatus upload : syncService.snapshotRecentFailedUploads(limit)) {
            Map<String, Object> item = uploadToMap(upload);
            enrichTimelineRisk(item);
            timeline.add(item);
        }
        int operatorCount = 0;
        for (MonitorActionRecord record : recentOperatorActions) {
            if (operatorCount >= limit) {
                break;
            }
            Map<String, Object> item = operatorActionTimelineItem(store, record);
            enrichTimelineRisk(item);
            timeline.add(item);
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

    private Map<String, Object> timelineRiskSummaryToMap(List<Map<String, Object>> timeline) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        int criticalCount = 0;
        int highCount = 0;
        int mediumCount = 0;
        int lowCount = 0;
        Map<String, Object> top = null;
        if (timeline != null) {
            for (Map<String, Object> item : timeline) {
                String riskLevel = stringValue(item.get("riskLevel"));
                if ("CRITICAL".equals(riskLevel)) {
                    criticalCount++;
                } else if ("HIGH".equals(riskLevel)) {
                    highCount++;
                } else if ("MEDIUM".equals(riskLevel)) {
                    mediumCount++;
                } else {
                    lowCount++;
                }
                if (top == null || compareTimelineRisk(item, top) < 0) {
                    top = item;
                }
            }
        }
        out.put("totalCount", Integer.valueOf(timeline == null ? 0 : timeline.size()));
        out.put("criticalCount", Integer.valueOf(criticalCount));
        out.put("highCount", Integer.valueOf(highCount));
        out.put("mediumCount", Integer.valueOf(mediumCount));
        out.put("lowCount", Integer.valueOf(lowCount));
        out.put("topRiskLevel", top == null ? "" : stringValue(top.get("riskLevel")));
        out.put("topRiskScore", Integer.valueOf(top == null ? 0 : intValue(top.get("riskScore"))));
        out.put("topFocusReason", top == null ? "" : stringValue(top.get("focusReason")));
        out.put("topPhase", top == null ? "" : stringValue(top.get("phase")));
        out.put("topPath", top == null ? "" : stringValue(top.get("path")));
        out.put("topMessage", top == null ? "" : stringValue(top.get("message")));
        return out;
    }

    private void enrichTimelineRisk(Map<String, Object> item) {
        String phase = stringValue(item.get("phase"));
        String riskLevel = "LOW";
        int riskScore = 100;
        String focusReason = "TIMELINE_EVENT";
        if ("operator_action".equals(phase)) {
            boolean success = boolValue(item.get("success"));
            int remainingFailedItemCount = intValue(item.get("remainingFailedItemCount"));
            int remainingOutstandingReplicaCount = intValue(item.get("remainingOutstandingReplicaCount"));
            int clearedFailedItemCount = intValue(item.get("clearedFailedItemCount"));
            int clearedOutstandingReplicaCount = intValue(item.get("clearedOutstandingReplicaCount"));
            if (!success) {
                riskLevel = "CRITICAL";
                riskScore = 450;
                focusReason = "OPERATOR_ACTION_FAILED";
            } else if (remainingFailedItemCount > 0 || remainingOutstandingReplicaCount > 0) {
                riskLevel = "HIGH";
                riskScore = 320 + remainingFailedItemCount * 10 + remainingOutstandingReplicaCount * 15;
                focusReason = "OPERATOR_ACTION_PENDING_REMAINS";
            } else if (clearedFailedItemCount > 0 || clearedOutstandingReplicaCount > 0) {
                riskLevel = "MEDIUM";
                riskScore = 210 + clearedFailedItemCount * 5 + clearedOutstandingReplicaCount * 8;
                focusReason = "OPERATOR_ACTION_CLEARED_FAILURES";
            } else {
                riskLevel = "LOW";
                riskScore = 110;
                focusReason = "OPERATOR_ACTION_COMPLETED";
            }
        } else if ("failed".equals(phase)) {
            String category = replicaReasonCategory(stringValue(item.get("message")));
            if ("CONFLICT".equals(category) || "RETRY_LIMIT".equals(category)) {
                riskLevel = "CRITICAL";
                riskScore = 420;
            } else {
                riskLevel = "HIGH";
                riskScore = 300;
            }
            focusReason = "UPLOAD_FAILED_" + category;
        } else if ("uploading".equals(phase)) {
            boolean segmented = boolValue(item.get("segmented"));
            int totalSegments = intValue(item.get("totalSegments"));
            int uploadedSegments = intValue(item.get("uploadedSegments"));
            long stalledMillis = longValue(item.get("stalledMillis"));
            if (segmented && totalSegments > 0 && uploadedSegments < totalSegments) {
                if (stalledMillis >= SEGMENT_UPLOAD_STALL_THRESHOLD_MILLIS) {
                    riskLevel = "HIGH";
                    riskScore = 280;
                    focusReason = "SEGMENT_UPLOAD_STALLED";
                } else {
                    riskLevel = "MEDIUM";
                    riskScore = 180;
                    focusReason = "SEGMENT_UPLOAD_IN_PROGRESS";
                }
            } else {
                riskLevel = "LOW";
                riskScore = 120;
                focusReason = "UPLOAD_IN_PROGRESS";
            }
        } else if ("completed".equals(phase)) {
            riskLevel = "LOW";
            riskScore = 80;
            focusReason = "UPLOAD_COMPLETED";
        }
        item.put("riskLevel", riskLevel);
        item.put("riskScore", Integer.valueOf(riskScore));
        item.put("focusReason", focusReason);
    }

    private int compareTimelineRisk(Map<String, Object> left, Map<String, Object> right) {
        int leftScore = intValue(left.get("riskScore"));
        int rightScore = intValue(right.get("riskScore"));
        if (leftScore != rightScore) {
            return rightScore - leftScore;
        }
        long leftTime = longValue(left.get("updatedAtMillis"));
        long rightTime = longValue(right.get("updatedAtMillis"));
        return leftTime < rightTime ? 1 : (leftTime == rightTime ? 0 : -1);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            String raw = ((String) value).trim();
            if (!raw.isEmpty()) {
                return Integer.parseInt(raw);
            }
        }
        return 0;
    }

    private long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            String raw = ((String) value).trim();
            if (!raw.isEmpty()) {
                return Long.parseLong(raw);
            }
        }
        return 0L;
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value instanceof String) {
            return Boolean.parseBoolean(((String) value).trim());
        }
        return false;
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
                int leftPriority = ((Integer) left.get("priorityScore")).intValue();
                int rightPriority = ((Integer) right.get("priorityScore")).intValue();
                if (leftPriority != rightPriority) {
                    return rightPriority - leftPriority;
                }
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
        List<SyncUploadStatus> activeUploads = syncService.snapshotActiveUploads(limit);

        HealthStats stats = new HealthStats();
        collectFailedHealth(stats, store, store.queueRef(QueueKey.FILE_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, false);
        collectFailedHealth(stats, store, store.queueRef(QueueKey.FILE_MODIFY, QueueStage.FAILED), FileSyncEventType.MODIFY, false);
        collectFailedHealth(stats, store, store.queueRef(QueueKey.FILE_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, false);
        collectFailedHealth(stats, store, store.queueRef(QueueKey.DIR_CREATE, QueueStage.FAILED), FileSyncEventType.CREATE, true);
        collectFailedHealth(stats, store, store.queueRef(QueueKey.DIR_DELETE, QueueStage.FAILED), FileSyncEventType.DELETE, true);
        collectActiveUploadHealth(stats, activeUploads);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("activeCount", Integer.valueOf(activeCount));
        out.put("failedCount", Integer.valueOf(stats.failedCount));
        out.put("uploadingCount", Integer.valueOf(activeUploads.size()));
        out.put("oldestFailedAtMillis", Long.valueOf(stats.oldestFailedAtMillis));
        out.put("maxRetryCount", Integer.valueOf(stats.maxRetryCount));
        out.put("stalledUploadCount", Integer.valueOf(stats.stalledUploadCount));
        out.put("maxStalledMillis", Long.valueOf(stats.maxStalledMillis));
        out.put("topStalledPath", stats.topStalledPath);
        out.put("topStalledReplicaLabel", stats.topStalledReplicaLabel);
        out.put("stalledReplicaSummary", stats.stalledReplicaSummary);
        out.put("stalledRecommendedAction", stats.stalledRecommendedAction);
        out.put("stalledOperatorHint", stats.stalledOperatorHint);
        out.put("resumedUploadCount", Integer.valueOf(stats.resumedUploadCount));
        out.put("resumedSegmentCount", Integer.valueOf(stats.resumedSegmentCount));
        out.put("topResumedPath", stats.topResumedPath);
        out.put("topResumedReplicaLabel", stats.topResumedReplicaLabel);
        out.put("topResumedSegments", Integer.valueOf(stats.topResumedSegments));
        out.put("resumedReplicaSummary", stats.resumedReplicaSummary);
        return out;
    }

    private void collectActiveUploadHealth(HealthStats stats, List<SyncUploadStatus> uploads) {
        if (uploads == null || uploads.isEmpty()) {
            return;
        }
        for (SyncUploadStatus upload : uploads) {
            if (upload == null) {
                continue;
            }
            if (!"uploading".equals(upload.getPhase())) {
                continue;
            }
            if (upload.isResumedUpload()) {
                stats.resumedUploadCount++;
                stats.resumedSegmentCount += upload.getResumedSegments();
                stats.recordResumedReplica(upload.getReplicaLabel(), upload.getResumedSegments());
                if (upload.getResumedSegments() > stats.topResumedSegments) {
                    stats.topResumedSegments = upload.getResumedSegments();
                    stats.topResumedPath = upload.getPath() == null ? "" : upload.getPath();
                    stats.topResumedReplicaLabel = normalizedReplicaLabel(upload.getReplicaLabel());
                }
            }
            if (!upload.isSegmented() || upload.getTotalSegments() <= 0 || upload.getUploadedSegments() >= upload.getTotalSegments()) {
                continue;
            }
            long lastProgressAtMillis = upload.getLastProgressAtMillis();
            long stalledMillis = lastProgressAtMillis <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - lastProgressAtMillis);
            if (stalledMillis < SEGMENT_UPLOAD_STALL_THRESHOLD_MILLIS) {
                continue;
            }
            stats.stalledUploadCount++;
            stats.recordStalledReplica(upload.getReplicaLabel());
            if (stalledMillis > stats.maxStalledMillis) {
                stats.maxStalledMillis = stalledMillis;
                stats.topStalledPath = upload.getPath() == null ? "" : upload.getPath();
                stats.topStalledReplicaLabel = normalizedReplicaLabel(upload.getReplicaLabel());
            }
        }
        stats.finishReplicaSummary();
        stats.finishResumedSummary();
        stats.finishStalledAdvice();
    }

    private static String normalizedReplicaLabel(String replicaLabel) {
        if (replicaLabel == null || replicaLabel.trim().isEmpty()) {
            return "default";
        }
        return replicaLabel.trim();
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
            String focusCategory = hotFailedFocusCategory(reason, replicaCategoryCounts);
            String priorityLevel = hotFailedPriorityLevel(retryCount, focusCategory, replicaStats);
            int priorityScore = hotFailedPriorityScore(retryCount, focusCategory, replicaStats);
            item.put("priorityLevel", priorityLevel);
            item.put("priorityScore", Integer.valueOf(priorityScore));
            item.put("focusReason", hotFailedFocusReason(retryCount, focusCategory, replicaStats));
            item.put("recommendedAction", recommendedActionForHotFailedItem(reason, replicaCategoryCounts, replicaStats));
            item.put("operatorHint", operatorHintForHotFailedItem(reason, replicaCategoryCounts, replicaStats));
            items.add(item);
        }
    }

    private String hotFailedFocusCategory(String reason, Map<String, Integer> replicaCategoryCounts) {
        String category = firstReplicaCategory(replicaCategoryCounts);
        if (category != null && !category.isBlank()) {
            return category;
        }
        return replicaReasonCategory(reason);
    }

    private String hotFailedPriorityLevel(int retryCount, String focusCategory, ReplicaRecoveryStats replicaStats) {
        int manualReplicaCount = replicaStats == null ? 0 : replicaStats.manualReplicaCount;
        int outstandingReplicaCount = replicaStats == null ? 0 : replicaStats.outstandingReplicaCount;
        if (manualReplicaCount > 0) {
            return "CRITICAL";
        }
        if (!isRetryable(retryCount) || outstandingReplicaCount > 0) {
            return "HIGH";
        }
        if (retryCount > 0 || "NETWORK".equals(focusCategory) || "STATE_MISMATCH".equals(focusCategory)) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private int hotFailedPriorityScore(int retryCount, String focusCategory, ReplicaRecoveryStats replicaStats) {
        int score;
        String level = hotFailedPriorityLevel(retryCount, focusCategory, replicaStats);
        if ("CRITICAL".equals(level)) {
            score = 400;
        } else if ("HIGH".equals(level)) {
            score = 300;
        } else if ("MEDIUM".equals(level)) {
            score = 200;
        } else {
            score = 100;
        }
        if (replicaStats != null) {
            score += replicaStats.manualReplicaCount * 25;
            score += replicaStats.outstandingReplicaCount * 10;
            score += replicaStats.autoRecoverableReplicaCount * 5;
        }
        score += Math.min(10, Math.max(0, retryCount)) * 5;
        if ("RETRY_LIMIT".equals(focusCategory) || "CONFLICT".equals(focusCategory)) {
            score += 10;
        } else if ("NETWORK".equals(focusCategory) || "STATE_MISMATCH".equals(focusCategory)) {
            score += 5;
        }
        return score;
    }

    private String hotFailedFocusReason(int retryCount, String focusCategory, ReplicaRecoveryStats replicaStats) {
        int manualReplicaCount = replicaStats == null ? 0 : replicaStats.manualReplicaCount;
        int outstandingReplicaCount = replicaStats == null ? 0 : replicaStats.outstandingReplicaCount;
        if (manualReplicaCount > 0 && "CONFLICT".equals(focusCategory)) {
            return "CONFLICT_REQUIRES_MANUAL_INTERVENTION";
        }
        if (manualReplicaCount > 0 && "RETRY_LIMIT".equals(focusCategory)) {
            return "RETRY_LIMIT_REQUIRES_MANUAL_INTERVENTION";
        }
        if (manualReplicaCount > 0) {
            return "MANUAL_REPLICA_INTERVENTION_REQUIRED";
        }
        if (!isRetryable(retryCount)) {
            return "AUTO_RETRY_CAPPED";
        }
        if ("STATE_MISMATCH".equals(focusCategory)) {
            return "STATE_CHECK_REQUIRED";
        }
        if ("NETWORK".equals(focusCategory)) {
            return outstandingReplicaCount > 0 ? "NETWORK_REPLICA_RECOVERY_PENDING" : "NETWORK_RECOVERY_PENDING";
        }
        if (outstandingReplicaCount > 0) {
            return "OUTSTANDING_REPLICAS_PENDING";
        }
        if (retryCount > 0) {
            return "RETRY_BACKLOG";
        }
        return "FAILED_ITEM_PENDING";
    }

    private Map<String, Object> uploadsToMap(int limit) {
        return uploadHistoryToMap(syncService.snapshotActiveUploads(limit));
    }

    private Map<String, Object> stalledUploadsToMap(int limit) {
        List<SyncUploadStatus> uploads = syncService.snapshotActiveUploads(limit);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (SyncUploadStatus upload : uploads) {
            if (upload == null) {
                continue;
            }
            Map<String, Object> item = uploadToMap(upload);
            if (longValue(item.get("stalledMillis")) < SEGMENT_UPLOAD_STALL_THRESHOLD_MILLIS) {
                continue;
            }
            items.add(item);
        }
        Collections.sort(items, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                long leftStalled = longValue(left.get("stalledMillis"));
                long rightStalled = longValue(right.get("stalledMillis"));
                if (leftStalled == rightStalled) {
                    return stringValue(left.get("path")).compareTo(stringValue(right.get("path")));
                }
                return leftStalled < rightStalled ? 1 : -1;
            }
        });
        if (limit > 0 && items.size() > limit) {
            items = new ArrayList<Map<String, Object>>(items.subList(0, limit));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("size", Integer.valueOf(items.size()));
        out.put("items", items);
        return out;
    }

    private Map<String, Object> stalledReplicaHotspotsToMap(int limit) {
        List<SyncUploadStatus> uploads = syncService.snapshotActiveUploads(limit);
        Map<String, StalledReplicaHotspot> hotspots = new LinkedHashMap<String, StalledReplicaHotspot>();
        for (SyncUploadStatus upload : uploads) {
            if (upload == null) {
                continue;
            }
            Map<String, Object> item = uploadToMap(upload);
            long stalledMillis = longValue(item.get("stalledMillis"));
            if (stalledMillis < SEGMENT_UPLOAD_STALL_THRESHOLD_MILLIS) {
                continue;
            }
            String replicaLabel = normalizedReplicaLabel(stringValue(item.get("replicaLabel")));
            StalledReplicaHotspot hotspot = hotspots.get(replicaLabel);
            if (hotspot == null) {
                hotspot = new StalledReplicaHotspot(replicaLabel);
                hotspots.put(replicaLabel, hotspot);
            }
            hotspot.record(stringValue(item.get("path")), stalledMillis);
        }
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (StalledReplicaHotspot hotspot : hotspots.values()) {
            items.add(hotspot.toMap());
        }
        Collections.sort(items, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                int leftCount = intValue(left.get("count"));
                int rightCount = intValue(right.get("count"));
                if (leftCount != rightCount) {
                    return leftCount < rightCount ? 1 : -1;
                }
                long leftStalled = longValue(left.get("maxStalledMillis"));
                long rightStalled = longValue(right.get("maxStalledMillis"));
                if (leftStalled != rightStalled) {
                    return leftStalled < rightStalled ? 1 : -1;
                }
                return stringValue(left.get("replicaLabel")).compareTo(stringValue(right.get("replicaLabel")));
            }
        });
        if (limit > 0 && items.size() > limit) {
            items = new ArrayList<Map<String, Object>>(items.subList(0, limit));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("size", Integer.valueOf(items.size()));
        out.put("items", items);
        return out;
    }

    private Map<String, Object> resumedReplicaHotspotsToMap(int limit) {
        List<SyncUploadStatus> uploads = syncService.snapshotActiveUploads(limit);
        Map<String, ResumedReplicaHotspot> hotspots = new LinkedHashMap<String, ResumedReplicaHotspot>();
        for (SyncUploadStatus upload : uploads) {
            if (upload == null || !upload.isResumedUpload()) {
                continue;
            }
            String replicaLabel = normalizedReplicaLabel(upload.getReplicaLabel());
            ResumedReplicaHotspot hotspot = hotspots.get(replicaLabel);
            if (hotspot == null) {
                hotspot = new ResumedReplicaHotspot(replicaLabel);
                hotspots.put(replicaLabel, hotspot);
            }
            hotspot.record(upload.getPath(), upload.getResumedSegments());
        }
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (ResumedReplicaHotspot hotspot : hotspots.values()) {
            items.add(hotspot.toMap());
        }
        Collections.sort(items, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                int leftSegments = intValue(left.get("resumedSegments"));
                int rightSegments = intValue(right.get("resumedSegments"));
                if (leftSegments != rightSegments) {
                    return leftSegments < rightSegments ? 1 : -1;
                }
                int leftCount = intValue(left.get("count"));
                int rightCount = intValue(right.get("count"));
                if (leftCount != rightCount) {
                    return leftCount < rightCount ? 1 : -1;
                }
                return stringValue(left.get("replicaLabel")).compareTo(stringValue(right.get("replicaLabel")));
            }
        });
        if (limit > 0 && items.size() > limit) {
            items = new ArrayList<Map<String, Object>>(items.subList(0, limit));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("size", Integer.valueOf(items.size()));
        out.put("items", items);
        return out;
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
        long lastProgressAtMillis = upload.getLastProgressAtMillis();
        long stalledMillis = lastProgressAtMillis <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - lastProgressAtMillis);
        boolean stalled = stalledMillis >= SEGMENT_UPLOAD_STALL_THRESHOLD_MILLIS;
        item.put("eventUid", Long.toString(upload.getEventUid()));
        item.put("fileId", Long.toString(upload.getFileId()));
        item.put("path", upload.getPath());
        item.put("phase", upload.getPhase());
        item.put("fileSize", Long.valueOf(upload.getFileSize()));
        item.put("segmented", Boolean.valueOf(upload.isSegmented()));
        item.put("totalSegments", Integer.valueOf(upload.getTotalSegments()));
        item.put("uploadedSegments", Integer.valueOf(upload.getUploadedSegments()));
        item.put("resumedSegments", Integer.valueOf(upload.getResumedSegments()));
        item.put("resumedUpload", Boolean.valueOf(upload.isResumedUpload()));
        item.put("startedAtMillis", Long.valueOf(upload.getStartedAtMillis()));
        item.put("updatedAtMillis", Long.valueOf(upload.getUpdatedAtMillis()));
        item.put("lastProgressAtMillis", Long.valueOf(lastProgressAtMillis));
        item.put("stalledMillis", Long.valueOf(stalledMillis));
        item.put("replicaLabel", upload.getReplicaLabel() == null ? "" : upload.getReplicaLabel());
        item.put("message", upload.getMessage() == null ? "" : upload.getMessage());
        item.put("recommendedAction", stalled ? recommendedActionForStalledUpload(upload) : "");
        item.put("operatorHint", stalled ? operatorHintForStalledUpload(upload) : "");
        return item;
    }

    private static String recommendedActionForStalledUpload(String replicaLabel) {
        String normalizedReplicaLabel = normalizedReplicaLabel(replicaLabel);
        if ("default".equals(normalizedReplicaLabel)) {
            return "CHECK_TRANSFER_PIPELINE";
        }
        return "CHECK_STALLED_REPLICA";
    }

    private static String recommendedActionForStalledUpload(SyncUploadStatus upload) {
        String replicaLabel = normalizedReplicaLabel(upload == null ? null : upload.getReplicaLabel());
        if ("default".equals(replicaLabel)) {
            return "CHECK_TRANSFER_PIPELINE";
        }
        return "CHECK_STALLED_REPLICA";
    }

    private static String operatorHintForStalledUpload(String path, String replicaLabel) {
        String normalizedReplicaLabel = normalizedReplicaLabel(replicaLabel);
        String safePath = path == null ? "" : path;
        if ("default".equals(normalizedReplicaLabel)) {
            return "优先检查发送端分片进度、目标端写入与临时块文件状态；确认 " + safePath + " 是否仍在持续推进。";
        }
        return "优先检查副本 " + normalizedReplicaLabel + " 的网络连通性、接收端写入与临时块状态；必要时再处理 " + safePath + " 的人工重试。";
    }

    private static String operatorHintForStalledUpload(SyncUploadStatus upload) {
        String replicaLabel = normalizedReplicaLabel(upload == null ? null : upload.getReplicaLabel());
        String path = upload == null || upload.getPath() == null ? "" : upload.getPath();
        if ("default".equals(replicaLabel)) {
            return "优先检查发送端分片进度、目标端写入与临时块文件状态；确认 " + path + " 是否仍在持续推进。";
        }
        return "优先检查副本 " + replicaLabel + " 的网络连通性、接收端写入与临时块状态；必要时再处理 " + path + " 的人工重试。";
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
        if ("stale".equals(raw) || "replicas_pending".equals(raw) || raw.contains("stale") || raw.contains("pending")
            || raw.contains("checksum") || raw.contains("length_mismatch")) {
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
        if ("stale".equals(raw) || "replicas_pending".equals(raw) || raw.contains("pending")
            || raw.contains("checksum") || raw.contains("length_mismatch")) {
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
            + "    .summary-grid{display:grid; grid-template-columns:repeat(auto-fit,minmax(220px,1fr)); gap:12px;}\n"
            + "    .summary-card{border:1px solid #d9dde7; border-radius:8px; padding:12px; background:#fafcff;}\n"
            + "    .summary-card.warn{background:#fff9f2; border-color:#f3c78a;}\n"
            + "    .summary-card.ok{background:#f4fbf6; border-color:#9fd4a8;}\n"
            + "    .summary-title{font-size:12px; color:#445; margin-bottom:6px;}\n"
            + "    .summary-value{font-size:24px; font-weight:700; color:#112; margin-bottom:4px;}\n"
            + "    .summary-meta{font-size:12px; color:#556; line-height:1.5; word-break:break-word;}\n"
            + "    .cockpit-grid{display:grid; grid-template-columns:1.3fr 1fr; gap:16px; align-items:start;}\n"
            + "    .cockpit-stack{display:flex; flex-direction:column; gap:16px;}\n"
            + "    .focus-grid{display:grid; grid-template-columns:repeat(auto-fit,minmax(280px,1fr)); gap:12px;}\n"
            + "    .focus-card{border:1px solid #d9dde7; border-radius:8px; padding:12px; background:#fff;}\n"
            + "    .focus-card h4{margin:0 0 8px 0; font-size:14px;}\n"
            + "    .focus-meta{font-size:12px; color:#556; line-height:1.5; word-break:break-word;}\n"
            + "    .action-panel{display:flex; flex-direction:column; gap:12px;}\n"
            + "    .action-row{display:flex; flex-wrap:wrap; gap:8px;}\n"
            + "    .action-note{font-size:12px; color:#556; line-height:1.5;}\n"
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
            + "      render(data.queues, data.queueMatrix, data.healthSummary, data.failureTrend, data.recoverySuccessSummary, data.failureSummary, data.failureRecoverySummary, data.replicaRecoverySummary, data.replicaFailureSummary, data.replicaFailureCategorySummary, data.hotFailedItems, data.recentOperatorActions, data.recentTimeline, data.timelineRiskSummary, data.stalledUploads, data.stalledReplicaHotspots, data.uploads, data.uploadPolicy, data.retryPolicy, data.recentCompletedUploads, data.recentFailedUploads);\n"
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
            + "      html += '<table><tr><th>path</th><th>replica</th><th>phase</th><th>size</th><th>segmented</th><th>resumedUpload</th><th>resumedSegments</th><th>progress</th><th>lastProgressAtMillis</th><th>stalledMillis</th></tr>';\n"
            + "      for(const it of u.items){\n"
            + "        const progress = it.totalSegments > 0 ? (it.uploadedSegments + '/' + it.totalSegments) : '-';\n"
            + "        html += '<tr><td>'+esc(it.path)+'</td><td>'+esc(it.replicaLabel || '')+'</td><td>'+esc(it.phase)+'</td><td>'+it.fileSize+'</td><td>'+it.segmented+'</td><td>'+it.resumedUpload+'</td><td>'+it.resumedSegments+'</td><td>'+progress+'</td><td>'+it.lastProgressAtMillis+'</td><td>'+it.stalledMillis+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderUploadHistory(title, u){\n"
            + "      let html = '<div class=\"card\"><h3>'+esc(title)+' (size='+u.size+')</h3>';\n"
            + "      html += '<table><tr><th>path</th><th>replica</th><th>phase</th><th>size</th><th>resumedUpload</th><th>resumedSegments</th><th>progress</th><th>lastProgressAtMillis</th><th>stalledMillis</th><th>recommendedAction</th><th>operatorHint</th><th>message</th></tr>';\n"
            + "      for(const it of u.items){\n"
            + "        const progress = it.totalSegments > 0 ? (it.uploadedSegments + '/' + it.totalSegments) : '-';\n"
            + "        html += '<tr><td>'+esc(it.path)+'</td><td>'+esc(it.replicaLabel || '')+'</td><td>'+esc(it.phase)+'</td><td>'+it.fileSize+'</td><td>'+it.resumedUpload+'</td><td>'+it.resumedSegments+'</td><td>'+progress+'</td><td>'+it.lastProgressAtMillis+'</td><td>'+it.stalledMillis+'</td><td>'+esc(it.recommendedAction || '')+'</td><td>'+esc(it.operatorHint || '')+'</td><td>'+esc(it.message)+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderStalledUploads(u){\n"
            + "      let html = '<div id=\"stalled-uploads\" class=\"card\"><h3>卡住上传 (size='+u.size+')</h3>';\n"
            + "      html += '<table><tr><th>path</th><th>replica</th><th>phase</th><th>size</th><th>resumedUpload</th><th>resumedSegments</th><th>progress</th><th>lastProgressAtMillis</th><th>stalledMillis</th><th>message</th></tr>';\n"
            + "      for(const it of u.items){\n"
            + "        const progress = it.totalSegments > 0 ? (it.uploadedSegments + '/' + it.totalSegments) : '-';\n"
            + "        html += '<tr><td>'+esc(it.path)+'</td><td>'+esc(it.replicaLabel || '')+'</td><td>'+esc(it.phase)+'</td><td>'+it.fileSize+'</td><td>'+it.resumedUpload+'</td><td>'+it.resumedSegments+'</td><td>'+progress+'</td><td>'+it.lastProgressAtMillis+'</td><td>'+it.stalledMillis+'</td><td>'+esc(it.message)+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderResumedReplicaHotspots(u){\n"
            + "      let html = '<div id=\"resumed-replica-hotspots\" class=\"card\"><h3>恢复副本热点 (size='+u.size+')</h3>';\n"
            + "      html += '<table><tr><th>replica</th><th>count</th><th>resumedSegments</th><th>topPath</th></tr>';\n"
            + "      for(const it of u.items){\n"
            + "        html += '<tr><td>'+esc(it.replicaLabel || '')+'</td><td>'+it.count+'</td><td>'+it.resumedSegments+'</td><td>'+esc(it.topPath || '')+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderStalledReplicaHotspots(u){\n"
            + "      let html = '<div class=\"card\"><h3>卡住副本热点 (size='+u.size+')</h3>';\n"
            + "      html += '<table><tr><th>replica</th><th>count</th><th>maxStalledMillis</th><th>topPath</th><th>recommendedAction</th><th>operatorHint</th></tr>';\n"
            + "      for(const it of u.items){\n"
            + "        html += '<tr><td>'+esc(it.replicaLabel || '')+'</td><td>'+it.count+'</td><td>'+it.maxStalledMillis+'</td><td>'+esc(it.topPath || '')+'</td><td>'+esc(it.recommendedAction || '')+'</td><td>'+esc(it.operatorHint || '')+'</td></tr>';\n"
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
            + "      html += '<table><tr><th>path</th><th>phase</th><th>riskLevel</th><th>riskScore</th><th>focusReason</th><th>cleared</th><th>remaining</th><th>updatedAtMillis</th><th>message</th></tr>';\n"
            + "      for(const it of t.items){\n"
            + "        html += '<tr><td>'+esc(it.path)+'</td><td>'+esc(it.phase)+'</td><td>'+esc(it.riskLevel || '')+'</td><td>'+esc(it.riskScore || 0)+'</td><td>'+esc(it.focusReason || '')+'</td><td>'+esc(formatDeltaSummary(it))+'</td><td>'+esc(formatRemainingSummary(it))+'</td><td>'+it.updatedAtMillis+'</td><td>'+esc(it.message)+'</td></tr>';\n"
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
            + "    function summaryCount(items, key, expected){\n"
            + "      if(!Array.isArray(items)){return 0;}\n"
            + "      for(const it of items){\n"
            + "        if(it && it[key] === expected){\n"
            + "          return it.count || 0;\n"
            + "        }\n"
            + "      }\n"
            + "      return 0;\n"
            + "    }\n"
            + "    function renderSummaryCard(title, value, meta, tone){\n"
            + "      let cls = 'summary-card';\n"
            + "      if(tone){ cls += ' ' + tone; }\n"
            + "      return '<div class=\"'+cls+'\"><div class=\"summary-title\">'+esc(title)+'</div><div class=\"summary-value\">'+esc(String(value))+'</div><div class=\"summary-meta\">'+esc(meta || '')+'</div></div>';\n"
            + "    }\n"
            + "    function renderFocusCard(title, value, meta, actionHtml, tone){\n"
            + "      let cls = 'focus-card';\n"
            + "      if(tone){ cls += ' ' + tone; }\n"
            + "      let html = '<div class=\"'+cls+'\"><h4>'+esc(title)+'</h4><div class=\"summary-value\">'+esc(String(value || '-'))+'</div><div class=\"focus-meta\">'+esc(meta || '')+'</div>';\n"
            + "      if(actionHtml){ html += '<div class=\"action-row\">'+actionHtml+'</div>'; }\n"
            + "      html += '</div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function normalizeTimelineCategory(topFocusReason){\n"
            + "      const text = String(topFocusReason || '');\n"
            + "      if(text.indexOf('CONFLICT') >= 0){ return 'CONFLICT'; }\n"
            + "      if(text.indexOf('RETRY_LIMIT') >= 0){ return 'RETRY_LIMIT'; }\n"
            + "      if(text.indexOf('NETWORK') >= 0){ return 'NETWORK'; }\n"
            + "      if(text.indexOf('STATE_CHECK') >= 0 || text.indexOf('STATE_MISMATCH') >= 0){ return 'STATE_MISMATCH'; }\n"
            + "      return '';\n"
            + "    }\n"
            + "    function hotItemMatchesTimeline(it, timelineRiskSummary){\n"
            + "      if(!it || !timelineRiskSummary){ return false; }\n"
            + "      const topPath = timelineRiskSummary.topPath || '';\n"
            + "      const topCategory = normalizeTimelineCategory(timelineRiskSummary.topFocusReason || '');\n"
            + "      if(topPath && it.path === topPath){ return true; }\n"
            + "      if(topCategory){\n"
            + "        const categorySummary = String(it.replicaCategorySummary || '');\n"
            + "        const reason = String(it.reason || '');\n"
            + "        const focusReason = String(it.focusReason || '');\n"
            + "        if(categorySummary.indexOf(topCategory) >= 0 || reason.indexOf(topCategory.toLowerCase()) >= 0 || focusReason.indexOf(topCategory) >= 0){\n"
            + "          return true;\n"
            + "        }\n"
            + "      }\n"
            + "      return false;\n"
            + "    }\n"
            + "    function pickLinkedHotItem(hotFailedItems, timelineRiskSummary){\n"
            + "      const hotItems = Array.isArray(hotFailedItems && hotFailedItems.items) ? hotFailedItems.items : [];\n"
            + "      for(const it of hotItems){\n"
            + "        if(hotItemMatchesTimeline(it, timelineRiskSummary)){\n"
            + "          return it;\n"
            + "        }\n"
            + "      }\n"
            + "      return hotItems.length > 0 ? hotItems[0] : null;\n"
            + "    }\n"
            + "    function renderTimelineHotspotLink(hotFailedItems, timelineRiskSummary){\n"
            + "      const linked = pickLinkedHotItem(hotFailedItems, timelineRiskSummary);\n"
            + "      if(!linked){\n"
            + "        return '<div class=\"card\"><h3>关联处置</h3><div class=\"action-note\">暂无可关联的热点失败项。</div></div>';\n"
            + "      }\n"
            + "      const timelineMeta = timelineRiskSummary && timelineRiskSummary.topRiskLevel\n"
            + "        ? ('timeline=' + (timelineRiskSummary.topRiskLevel || '-') + '/' + (timelineRiskSummary.topFocusReason || '') + ', path=' + (timelineRiskSummary.topPath || ''))\n"
            + "        : 'timeline=无高风险事件';\n"
            + "      const hotMeta = 'hot=' + (linked.priorityLevel || '') + '/' + (linked.priorityScore || 0)\n"
            + "        + ', action=' + (linked.recommendedAction || '')\n"
            + "        + ', hint=' + (linked.operatorHint || '');\n"
            + "      return renderFocusCard('关联处置', linked.path || '-', timelineMeta + ', ' + hotMeta, renderHotFailedItemActionButtons(linked), 'warn');\n"
            + "    }\n"
            + "    function remainingCategoryNextStep(summary){\n"
            + "      const text = String(summary || '');\n"
            + "      if(text.indexOf('NETWORK') >= 0){ return '优先执行 NETWORK 重试'; }\n"
            + "      if(text.indexOf('CONFLICT') >= 0 || text.indexOf('RETRY_LIMIT') >= 0){ return '优先人工重试或放弃 CONFLICT/RETRY_LIMIT'; }\n"
            + "      if(text.indexOf('STATE_MISMATCH') >= 0){ return '先校验状态再重试 STATE_MISMATCH'; }\n"
            + "      return '回到失败类别入口继续批量处置';\n"
            + "    }\n"
            + "    function renderRemainingCategoryActionButtons(summary){\n"
            + "      const text = String(summary || '');\n"
            + "      const buttons = [];\n"
            + "      if(text.indexOf('NETWORK') >= 0){\n"
            + "        buttons.push('<button class=\"btn\" data-category-action=\"retry\" data-category=\"NETWORK\">继续重试 NETWORK</button>');\n"
            + "      }\n"
            + "      if(text.indexOf('CONFLICT') >= 0){\n"
            + "        buttons.push('<button class=\"btn\" data-category-action=\"retry\" data-category=\"CONFLICT\">人工重试 CONFLICT</button>');\n"
            + "        buttons.push('<button class=\"btn\" data-category-action=\"discard\" data-category=\"CONFLICT\">放弃 CONFLICT</button>');\n"
            + "      }\n"
            + "      if(text.indexOf('RETRY_LIMIT') >= 0){\n"
            + "        buttons.push('<button class=\"btn\" data-category-action=\"retry\" data-category=\"RETRY_LIMIT\">人工重试 RETRY_LIMIT</button>');\n"
            + "        buttons.push('<button class=\"btn\" data-category-action=\"discard\" data-category=\"RETRY_LIMIT\">放弃 RETRY_LIMIT</button>');\n"
            + "      }\n"
            + "      if(text.indexOf('STATE_MISMATCH') >= 0){\n"
            + "        buttons.push('<button class=\"btn\" data-category-action=\"retry\" data-category=\"STATE_MISMATCH\">校验后重试 STATE_MISMATCH</button>');\n"
            + "      }\n"
            + "      return buttons.length > 0 ? '<div class=\"action-row\">' + buttons.join(' ') + '</div>' : '';\n"
            + "    }\n"
            + "    function renderRemainingFailureSample(lastBatchResult, linkedHot, firstAction){\n"
            + "      const previewItems = Array.isArray(lastBatchResult && lastBatchResult.remainingFailedItemsPreview) ? lastBatchResult.remainingFailedItemsPreview : [];\n"
            + "      const sample = previewItems.length > 0 ? previewItems[0] : linkedHot;\n"
            + "      const remainingSummary = firstAction ? (firstAction.remainingReplicaCategorySummary || '') : (lastBatchResult && lastBatchResult.remainingReplicaCategorySummary) || '';\n"
            + "      if(!sample && !remainingSummary){\n"
            + "        return renderFocusCard('5. 剩余失败样本', '无剩余样本', '当前没有 remainingFailedItemsPreview 或剩余类别。', '', 'ok');\n"
            + "      }\n"
            + "      const value = sample ? (sample.path || '-') : '无明确样本';\n"
            + "      let meta = 'remainingCategories=' + (remainingSummary || '');\n"
            + "      if(sample){\n"
            + "        meta += ', focus=' + (sample.focusReason || '') + ', action=' + (sample.recommendedAction || '') + ', hint=' + (sample.operatorHint || '');\n"
            + "      }\n"
            + "      meta += ', next=' + remainingCategoryNextStep(remainingSummary);\n"
            + "      const sampleActions = sample ? renderHotFailedItemActionButtons(sample) : '';\n"
            + "      const categoryActions = renderRemainingCategoryActionButtons(remainingSummary);\n"
            + "      const actionHtml = sampleActions + (sampleActions && categoryActions ? ' ' : '') + categoryActions;\n"
            + "      return renderFocusCard('5. 剩余失败样本', value, meta, actionHtml, remainingSummary ? 'warn' : 'ok');\n"
            + "    }\n"
            + "    function operatorActionDigest(firstAction, lastBatchResult, linkedHot){\n"
            + "      const previewItems = Array.isArray(lastBatchResult && lastBatchResult.remainingFailedItemsPreview) ? lastBatchResult.remainingFailedItemsPreview : [];\n"
            + "      const sample = previewItems.length > 0 ? previewItems[0] : linkedHot;\n"
            + "      const previewPaths = (lastBatchResult && lastBatchResult.remainingFailedPathsSummary) || (sample && sample.path) || '';\n"
            + "      const remainingSummary = firstAction ? (firstAction.remainingReplicaCategorySummary || '') : (lastBatchResult && lastBatchResult.remainingReplicaCategorySummary) || '';\n"
            + "      const result = {value:'继续观察', summary:'暂无运维动作', tone:'ok', actionHtml:'', remainingSummary:remainingSummary, previewPaths:previewPaths};\n"
            + "      const sampleActions = sample ? renderHotFailedItemActionButtons(sample) : '';\n"
            + "      const categoryActions = renderRemainingCategoryActionButtons(remainingSummary);\n"
            + "      if(firstAction){\n"
            + "        const remainingFailedItems = firstAction.remainingFailedItemCount || 0;\n"
            + "        const remainingOutstandingReplicas = firstAction.remainingOutstandingReplicaCount || 0;\n"
            + "        result.summary = 'action=' + (firstAction.action || '-') + ', cleared=' + formatDeltaSummary(firstAction) + ', remaining=' + formatRemainingSummary(firstAction);\n"
            + "        if(previewPaths){ result.summary += ', preview=' + previewPaths; }\n"
            + "        if(remainingFailedItems > 0 || remainingOutstandingReplicas > 0){\n"
            + "          result.value = '继续收口';\n"
            + "          result.tone = 'warn';\n"
            + "          result.summary += ', next=' + remainingCategoryNextStep(remainingSummary);\n"
            + "          result.actionHtml = sampleActions + (sampleActions && categoryActions ? ' ' : '') + categoryActions;\n"
            + "        } else if(firstAction.success){\n"
            + "          result.value = '已收口';\n"
            + "          result.tone = 'ok';\n"
            + "          result.summary += ', next=当前批次已完成收口';\n"
            + "        } else {\n"
            + "          result.value = '动作失败';\n"
            + "          result.tone = 'warn';\n"
            + "          result.summary += ', next=回到失败类别入口继续批量处置';\n"
            + "          result.actionHtml = sampleActions || categoryActions;\n"
            + "        }\n"
            + "      } else if(sample || remainingSummary){\n"
            + "        result.summary = 'remainingCategories=' + (remainingSummary || '');\n"
            + "        if(previewPaths){ result.summary += ', preview=' + previewPaths; }\n"
            + "        result.summary += ', next=' + remainingCategoryNextStep(remainingSummary);\n"
            + "        result.tone = remainingSummary ? 'warn' : 'ok';\n"
            + "        result.actionHtml = sampleActions + (sampleActions && categoryActions ? ' ' : '') + categoryActions;\n"
            + "      }\n"
            + "      return result;\n"
            + "    }\n"
            + "    function renderOperatorActionDigestCard(firstAction, lastBatchResult, linkedHot){\n"
            + "      const digest = operatorActionDigest(firstAction, lastBatchResult, linkedHot);\n"
            + "      return renderFocusCard('最近运维动作摘要', digest.value, digest.summary, digest.actionHtml, digest.tone);\n"
            + "    }\n"
            + "    function renderClosureOutcomeCard(lastBatchResult, linkedHot, firstAction){\n"
            + "      const digest = operatorActionDigest(firstAction, lastBatchResult, linkedHot);\n"
            + "      return renderFocusCard('3. 收口与后续动作', digest.value, digest.summary, digest.actionHtml, digest.tone);\n"
            + "    }\n"
            + "    function renderFailureActionFlow(replicaFailureCategorySummary, hotFailedItems, recentOperatorActions, timelineRiskSummary, lastBatchResult){\n"
            + "      const categoryItems = Array.isArray(replicaFailureCategorySummary && replicaFailureCategorySummary.items) ? replicaFailureCategorySummary.items : [];\n"
            + "      const operatorItems = Array.isArray(recentOperatorActions && recentOperatorActions.items) ? recentOperatorActions.items : [];\n"
            + "      const firstCategory = categoryItems.length > 0 ? categoryItems[0] : null;\n"
            + "      const linkedHot = pickLinkedHotItem(hotFailedItems, timelineRiskSummary);\n"
            + "      const firstAction = operatorItems.length > 0 ? operatorItems[0] : null;\n"
            + "      let html = '<div class=\"card\"><h3>处置链路</h3><div class=\"focus-grid\">';\n"
            + "      html += renderFocusCard('1. 失败类别入口', firstCategory ? (firstCategory.reason || '-') : '-', firstCategory ? ('count=' + (firstCategory.count || 0) + ', action=' + (firstCategory.recommendedAction || '') + ', hint=' + (firstCategory.operatorHint || '')) : '暂无副本失败类别', firstCategory ? renderReplicaCategoryActionButtons(firstCategory) : '', firstCategory ? 'warn' : 'ok');\n"
            + "      html += renderFocusCard('2. 热点失败落点', linkedHot ? (linkedHot.path || '-') : '-', linkedHot ? ('priority=' + (linkedHot.priorityLevel || '') + '/' + (linkedHot.priorityScore || 0) + ', focus=' + (linkedHot.focusReason || '') + ', action=' + (linkedHot.recommendedAction || '')) : '暂无热点失败项', linkedHot ? renderHotFailedItemActionButtons(linkedHot) : '', linkedHot ? 'warn' : 'ok');\n"
            + "      html += renderClosureOutcomeCard(lastBatchResult, linkedHot, firstAction);\n"
            + "      html += '</div><div class=\"action-note\">先按类别选择批量动作，再查看关联热点失败项，最后用最近运维动作的 cleared/remaining 判断是否真正收口。</div></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderActionOutcomeGuidance(linkedHot, firstAction){\n"
            + "      let title = '4. 下一步建议';\n"
            + "      let value = '继续观察';\n"
            + "      let meta = '暂无最新运维动作，先从失败类别入口或关联热点失败项发起处置。';\n"
            + "      let tone = 'ok';\n"
            + "      let actionHtml = linkedHot ? renderHotFailedItemActionButtons(linkedHot) : '';\n"
            + "      if(firstAction){\n"
            + "        const remainingFailedItems = firstAction.remainingFailedItemCount || 0;\n"
            + "        const remainingOutstandingReplicas = firstAction.remainingOutstandingReplicaCount || 0;\n"
            + "        if(remainingFailedItems > 0 || remainingOutstandingReplicas > 0){\n"
            + "          value = '继续收口';\n"
            + "          tone = 'warn';\n"
            + "          meta = 'remaining=' + formatRemainingSummary(firstAction);\n"
            + "          actionHtml += renderRemainingCategoryActionButtons(firstAction.remainingReplicaCategorySummary || '');\n"
            + "          if(linkedHot){\n"
            + "            meta += ', next=' + (linkedHot.recommendedAction || '') + ', hint=' + (linkedHot.operatorHint || '');\n"
            + "          } else {\n"
            + "            meta += ', next=回到失败类别入口继续批量处置';\n"
            + "          }\n"
            + "        } else if(firstAction.success){\n"
            + "          value = '已收口';\n"
            + "          tone = 'ok';\n"
            + "          meta = 'cleared=' + formatDeltaSummary(firstAction) + ', remaining=' + formatRemainingSummary(firstAction) + ', 当前批次已完成收口。';\n"
            + "          actionHtml = '';\n"
            + "        } else {\n"
            + "          value = '动作失败';\n"
            + "          tone = 'warn';\n"
            + "          meta = '最近运维动作未成功，建议回到关联热点失败项重新选择动作。';\n"
            + "        }\n"
            + "      }\n"
            + "      return renderFocusCard(title, value, meta, actionHtml, tone);\n"
            + "    }\n"
            + "    function renderQuickActions(replicaRecoverySummary, timelineRiskSummary, hotFailedItems){\n"
            + "      const firstHot = pickLinkedHotItem(hotFailedItems, timelineRiskSummary);\n"
            + "      let html = '<div class=\"card\"><h3>快速动作</h3><div class=\"action-panel\">';\n"
            + "      html += '<div class=\"action-note\">先处理高风险副本和热点失败；人工重试/放弃不受自动重试上限约束。</div>';\n"
            + "      html += '<div class=\"action-row\">';\n"
            + "      html += '<button class=\"btn\" data-batch-action=\"retry-auto-recoverable-replicas\">批量重试可自动恢复副本</button>';\n"
            + "      html += '<button class=\"btn\" data-batch-action=\"retry-network-replicas\">批量重试 NETWORK 副本</button>';\n"
            + "      html += '<button class=\"btn\" data-batch-action=\"discard-manual-replicas\">批量放弃人工介入副本</button>';\n"
            + "      html += '<button class=\"btn\" data-batch-action=\"discard-conflict-retry-limit-replicas\">批量放弃 CONFLICT/RETRY_LIMIT 副本</button>';\n"
            + "      html += '</div>';\n"
            + "      if(firstHot){\n"
            + "        html += '<div class=\"action-note\">关联热点建议：'+esc(firstHot.path || '-')+' | '+esc(firstHot.recommendedAction || '')+' | '+esc(firstHot.operatorHint || '')+'</div>';\n"
            + "        html += '<div class=\"action-row\">'+renderHotFailedItemActionButtons(firstHot)+'</div>';\n"
            + "      }\n"
            + "      if(timelineRiskSummary && timelineRiskSummary.topRiskLevel){\n"
            + "        html += '<div class=\"action-note\">时间线风险焦点：'+esc(timelineRiskSummary.topRiskLevel || '-')+' / '+esc(timelineRiskSummary.topFocusReason || '')+' / '+esc(timelineRiskSummary.topPath || '')+'</div>';\n"
            + "      }\n"
            + "      html += '<div class=\"action-note\">副本待处置：'+((replicaRecoverySummary && replicaRecoverySummary.totalOutstandingReplicas) || 0)+'，优先收口 manual 与 NETWORK 场景。</div>';\n"
            + "      html += '</div></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderCockpitFocus(healthSummary, hotFailedItems, recentOperatorActions, timelineRiskSummary){\n"
            + "      const hotItems = Array.isArray(hotFailedItems && hotFailedItems.items) ? hotFailedItems.items : [];\n"
            + "      const operatorItems = Array.isArray(recentOperatorActions && recentOperatorActions.items) ? recentOperatorActions.items : [];\n"
            + "      const firstHot = pickLinkedHotItem(hotFailedItems, timelineRiskSummary);\n"
            + "      const firstAction = operatorItems.length > 0 ? operatorItems[0] : null;\n"
            + "      const lastBatchResult = window.lastBatchResult || null;\n"
            + "      const stalledCount = healthSummary && healthSummary.stalledUploadCount ? healthSummary.stalledUploadCount : 0;\n"
            + "      const resumedCount = healthSummary && healthSummary.resumedUploadCount ? healthSummary.resumedUploadCount : 0;\n"
            + "      const stalledMeta = stalledCount > 0 ? ('maxStalledMillis=' + (healthSummary.maxStalledMillis || 0) + ', path=' + (healthSummary.topStalledPath || '') + ', replica=' + (healthSummary.topStalledReplicaLabel || '') + ', replicas=' + (healthSummary.stalledReplicaSummary || '') + ', action=' + (healthSummary.stalledRecommendedAction || '') + ', hint=' + (healthSummary.stalledOperatorHint || '')) : '暂无卡住上传';\n"
            + "      const resumedMeta = resumedCount > 0 ? ('resumedSegments=' + (healthSummary.resumedSegmentCount || 0) + ', path=' + (healthSummary.topResumedPath || '') + ', replica=' + (healthSummary.topResumedReplicaLabel || '') + ', topResumedSegments=' + (healthSummary.topResumedSegments || 0) + ', replicas=' + (healthSummary.resumedReplicaSummary || '')) : '暂无恢复上传';\n"
            + "      const stalledAction = stalledCount > 0 ? '<button class=\"btn\" data-scroll=\"stalled-uploads\">查看卡住上传</button>' : '';\n"
            + "      const resumedAction = resumedCount > 0 ? '<button class=\"btn\" data-scroll=\"resumed-replica-hotspots\">查看恢复热点</button>' : '';\n"
            + "      let html = '<div class=\"card\"><h3>焦点事件</h3><div class=\"focus-grid\">';\n"
            + "      html += renderFocusCard('热点失败项', firstHot ? (firstHot.path || '-') : '-', firstHot ? ('priority=' + (firstHot.priorityLevel || '') + '/' + (firstHot.priorityScore || 0) + ', focus=' + (firstHot.focusReason || '') + ', action=' + (firstHot.recommendedAction || '')) : '暂无热点失败项', firstHot ? renderHotFailedItemActionButtons(firstHot) : '', firstHot ? 'warn' : 'ok');\n"
            + "      html += renderFocusCard('时间线高风险事件', timelineRiskSummary && timelineRiskSummary.topRiskLevel ? (timelineRiskSummary.topRiskLevel || '-') : '-', timelineRiskSummary && timelineRiskSummary.topRiskLevel ? ('score=' + (timelineRiskSummary.topRiskScore || 0) + ', phase=' + (timelineRiskSummary.topPhase || '') + ', path=' + (timelineRiskSummary.topPath || '') + ', focus=' + (timelineRiskSummary.topFocusReason || '')) : '暂无时间线高风险事件', '', timelineRiskSummary && ((timelineRiskSummary.criticalCount || 0) > 0 || (timelineRiskSummary.highCount || 0) > 0) ? 'warn' : 'ok');\n"
            + "      html += renderFocusCard('卡住上传焦点', stalledCount, stalledMeta, stalledAction, stalledCount > 0 ? 'warn' : 'ok');\n"
            + "      html += renderFocusCard('恢复上传焦点', resumedCount, resumedMeta, resumedAction, resumedCount > 0 ? 'warn' : 'ok');\n"
            + "      html += renderTimelineHotspotLink(hotFailedItems, timelineRiskSummary);\n"
            + "      html += renderOperatorActionDigestCard(firstAction, lastBatchResult, firstHot);\n"
            + "      html += '</div></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderOpsCockpit(healthSummary, failureTrend, recoverySuccessSummary, replicaRecoverySummary, hotFailedItems, recentOperatorActions, recentTimeline, timelineRiskSummary){\n"
            + "      const replicaRecoveryItems = Array.isArray(replicaRecoverySummary && replicaRecoverySummary.items) ? replicaRecoverySummary.items : [];\n"
            + "      const hotItems = Array.isArray(hotFailedItems && hotFailedItems.items) ? hotFailedItems.items : [];\n"
            + "      const operatorItems = Array.isArray(recentOperatorActions && recentOperatorActions.items) ? recentOperatorActions.items : [];\n"
            + "      const firstHot = hotItems.length > 0 ? hotItems[0] : null;\n"
            + "      const firstAction = operatorItems.length > 0 ? operatorItems[0] : null;\n"
            + "      const digest = operatorActionDigest(firstAction, window.lastBatchResult || null, firstHot);\n"
            + "      const autoRecoverable = summaryCount(replicaRecoveryItems, 'recoveryClass', 'AUTO_RECOVERABLE');\n"
            + "      const manualIntervention = summaryCount(replicaRecoveryItems, 'recoveryClass', 'MANUAL_INTERVENTION');\n"
            + "      let html = '<div class=\"card\"><h3>风险总览</h3><div class=\"summary-grid\">';\n"
            + "      html += renderSummaryCard('失败总量', healthSummary.failedCount || 0, 'active=' + (healthSummary.activeCount || 0) + ', uploading=' + (healthSummary.uploadingCount || 0), (healthSummary.failedCount || 0) > 0 ? 'warn' : 'ok');\n"
            + "      html += renderSummaryCard('副本待处置', replicaRecoverySummary.totalOutstandingReplicas || 0, 'auto=' + autoRecoverable + ', manual=' + manualIntervention, (replicaRecoverySummary.totalOutstandingReplicas || 0) > 0 ? 'warn' : 'ok');\n"
            + "      html += renderSummaryCard('卡住上传', healthSummary.stalledUploadCount || 0, (healthSummary.stalledUploadCount || 0) > 0 ? ('maxStalledMillis=' + (healthSummary.maxStalledMillis || 0) + ', path=' + (healthSummary.topStalledPath || '') + ', replica=' + (healthSummary.topStalledReplicaLabel || '') + ', replicas=' + (healthSummary.stalledReplicaSummary || '') + ', action=' + (healthSummary.stalledRecommendedAction || '') + ', hint=' + (healthSummary.stalledOperatorHint || '')) : '暂无卡住上传', (healthSummary.stalledUploadCount || 0) > 0 ? 'warn' : 'ok');\n"
            + "      html += renderSummaryCard('恢复上传', healthSummary.resumedUploadCount || 0, (healthSummary.resumedUploadCount || 0) > 0 ? ('resumedSegments=' + (healthSummary.resumedSegmentCount || 0) + ', path=' + (healthSummary.topResumedPath || '') + ', replica=' + (healthSummary.topResumedReplicaLabel || '') + ', topResumedSegments=' + (healthSummary.topResumedSegments || 0) + ', replicas=' + (healthSummary.resumedReplicaSummary || '')) : '暂无恢复上传', (healthSummary.resumedUploadCount || 0) > 0 ? 'warn' : 'ok');\n"
            + "      html += renderSummaryCard('近 5 分钟失败', failureTrend.failedLast5MinutesCount || 0, '60m=' + (failureTrend.failedLast60MinutesCount || 0) + ', outstanding=' + (failureTrend.outstandingFailedCount || 0), (failureTrend.failedLast5MinutesCount || 0) > 0 ? 'warn' : 'ok');\n"
            + "      html += renderSummaryCard('恢复成功率', (recoverySuccessSummary.successRatePercent || 0) + '%', 'completed=' + (recoverySuccessSummary.completedCount || 0) + ', failed=' + (recoverySuccessSummary.failedCount || 0), (recoverySuccessSummary.successRatePercent || 0) >= 80 ? 'ok' : 'warn');\n"
            + "      html += renderSummaryCard('热点失败焦点', firstHot ? (firstHot.path || '-') : '-', firstHot ? ('priority=' + (firstHot.priorityLevel || '') + '/' + (firstHot.priorityScore || 0) + ', focus=' + (firstHot.focusReason || '') + ', action=' + (firstHot.recommendedAction || '')) : '暂无热点失败', firstHot ? 'warn' : 'ok');\n"
            + "      html += renderSummaryCard('最近运维动作摘要', digest.value, digest.summary, digest.tone);\n"
            + "      html += renderSummaryCard('时间线风险焦点', (timelineRiskSummary && timelineRiskSummary.topRiskLevel) ? timelineRiskSummary.topRiskLevel : '-', (timelineRiskSummary && timelineRiskSummary.topRiskLevel) ? ('score=' + (timelineRiskSummary.topRiskScore || 0) + ', phase=' + (timelineRiskSummary.topPhase || '') + ', path=' + (timelineRiskSummary.topPath || '') + ', focus=' + (timelineRiskSummary.topFocusReason || '')) : '暂无时间线风险事件', (timelineRiskSummary && ((timelineRiskSummary.criticalCount || 0) > 0 || (timelineRiskSummary.highCount || 0) > 0)) ? 'warn' : 'ok');\n"
            + "      html += '</div></div>';\n"
            + "      return html;\n"
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
            + "      html += '<table><tr><th>path</th><th>type</th><th>priorityLevel</th><th>priorityScore</th><th>focusReason</th><th>retryCount</th><th>remainingRetries</th><th>retryable</th><th>recoveryClass</th><th>replicaRecoveryClass</th><th>replicas</th><th>replicaCategories</th><th>replicaReasons</th><th>outstandingReplicas</th><th>failedAtMillis</th><th>reason</th><th>recommendedAction</th><th>operatorHint</th><th>action</th></tr>';\n"
            + "      for(const it of h.items){\n"
            + "        const outstandingReplicas = (it.outstandingReplicaCount || 0) + ' (auto=' + (it.autoRecoverableReplicaCount || 0) + ', manual=' + (it.manualReplicaCount || 0) + ')';\n"
            + "        html += '<tr><td>'+esc(it.path)+'</td><td>'+esc(it.type)+'</td><td>'+esc(it.priorityLevel || '')+'</td><td>'+esc(it.priorityScore || 0)+'</td><td>'+esc(it.focusReason || '')+'</td><td>'+it.retryCount+'</td><td>'+it.remainingRetries+'</td><td>'+(it.retryable ? 'yes' : 'capped')+'</td><td>'+esc(it.recoveryClass)+'</td><td>'+esc(it.replicaRecoveryClass || '')+'</td><td>'+esc(it.replicaSummary || '')+'</td><td>'+esc(it.replicaCategorySummary || '')+'</td><td>'+esc(it.replicaReasonSummary || '')+'</td><td>'+esc(outstandingReplicas)+'</td><td>'+it.failedAtMillis+'</td><td>'+esc(it.reason)+'</td><td>'+esc(it.recommendedAction || '')+'</td><td>'+esc(it.operatorHint || '')+'</td><td>'+renderHotFailedItemActionButtons(it)+'</td></tr>';\n"
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
            + "      html += '<table><tr><th>path</th><th>type</th><th>priorityLevel</th><th>priorityScore</th><th>focusReason</th><th>retryCount</th><th>remainingRetries</th><th>retryable</th><th>recoveryClass</th><th>replicaRecoveryClass</th><th>replicas</th><th>replicaCategories</th><th>replicaReasons</th><th>outstandingReplicas</th><th>failedAtMillis</th><th>reason</th><th>recommendedAction</th><th>operatorHint</th><th>action</th></tr>';\n"
            + "      for(const it of b.remainingFailedItemsPreview){\n"
            + "        const outstandingReplicas = (it.outstandingReplicaCount || 0) + ' (auto=' + (it.autoRecoverableReplicaCount || 0) + ', manual=' + (it.manualReplicaCount || 0) + ')';\n"
            + "        html += '<tr><td>'+esc(it.path)+'</td><td>'+esc(it.type)+'</td><td>'+esc(it.priorityLevel || '')+'</td><td>'+esc(it.priorityScore || 0)+'</td><td>'+esc(it.focusReason || '')+'</td><td>'+it.retryCount+'</td><td>'+it.remainingRetries+'</td><td>'+(it.retryable ? 'yes' : 'capped')+'</td><td>'+esc(it.recoveryClass)+'</td><td>'+esc(it.replicaRecoveryClass || '')+'</td><td>'+esc(it.replicaSummary || '')+'</td><td>'+esc(it.replicaCategorySummary || '')+'</td><td>'+esc(it.replicaReasonSummary || '')+'</td><td>'+esc(outstandingReplicas)+'</td><td>'+it.failedAtMillis+'</td><td>'+esc(it.reason)+'</td><td>'+esc(it.recommendedAction || '')+'</td><td>'+esc(it.operatorHint || '')+'</td><td>'+renderHotFailedItemActionButtons(it)+'</td></tr>';\n"
            + "      }\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    function renderHealthSummary(h){\n"
            + "      let html = '<div class=\"card\"><h3>队列健康概览</h3>';\n"
            + "      html += '<table><tr><th>activeCount</th><th>failedCount</th><th>uploadingCount</th><th>stalledUploadCount</th><th>maxStalledMillis</th><th>topStalledPath</th><th>topStalledReplicaLabel</th><th>stalledReplicaSummary</th><th>stalledRecommendedAction</th><th>stalledOperatorHint</th><th>resumedUploadCount</th><th>resumedSegmentCount</th><th>topResumedPath</th><th>topResumedReplicaLabel</th><th>topResumedSegments</th><th>resumedReplicaSummary</th><th>oldestFailedAtMillis</th><th>maxRetryCount</th></tr>';\n"
            + "      html += '<tr><td>'+h.activeCount+'</td><td>'+h.failedCount+'</td><td>'+h.uploadingCount+'</td><td>'+h.stalledUploadCount+'</td><td>'+h.maxStalledMillis+'</td><td>'+esc(h.topStalledPath || '')+'</td><td>'+esc(h.topStalledReplicaLabel || '')+'</td><td>'+esc(h.stalledReplicaSummary || '')+'</td><td>'+esc(h.stalledRecommendedAction || '')+'</td><td>'+esc(h.stalledOperatorHint || '')+'</td><td>'+h.resumedUploadCount+'</td><td>'+h.resumedSegmentCount+'</td><td>'+esc(h.topResumedPath || '')+'</td><td>'+esc(h.topResumedReplicaLabel || '')+'</td><td>'+h.topResumedSegments+'</td><td>'+esc(h.resumedReplicaSummary || '')+'</td><td>'+h.oldestFailedAtMillis+'</td><td>'+h.maxRetryCount+'</td></tr>';\n"
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
            + "    function render(queues, queueMatrix, healthSummary, failureTrend, recoverySuccessSummary, failureSummary, failureRecoverySummary, replicaRecoverySummary, replicaFailureSummary, replicaFailureCategorySummary, hotFailedItems, recentOperatorActions, recentTimeline, timelineRiskSummary, stalledUploads, stalledReplicaHotspots, uploads, uploadPolicy, retryPolicy, recentCompletedUploads, recentFailedUploads){\n"
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
            + "      const safeHealthSummary = healthSummary || {activeCount:0, failedCount:0, uploadingCount:0, stalledUploadCount:0, maxStalledMillis:0, topStalledPath:'', topStalledReplicaLabel:'', stalledReplicaSummary:'', stalledRecommendedAction:'', stalledOperatorHint:'', resumedUploadCount:0, resumedSegmentCount:0, topResumedPath:'', topResumedReplicaLabel:'', topResumedSegments:0, resumedReplicaSummary:'', oldestFailedAtMillis:0, maxRetryCount:0};\n"
            + "      const safeFailureTrend = failureTrend || {recentFailedCount:0, failedLast5MinutesCount:0, failedLast60MinutesCount:0, outstandingFailedCount:0, latestFailedAtMillis:0};\n"
            + "      const safeRecoverySuccessSummary = recoverySuccessSummary || {completedCount:0, failedCount:0, totalCount:0, successRatePercent:0, avgCompletedDurationMillis:0, avgFailedDurationMillis:0, lastCompletedAtMillis:0, lastFailedAtMillis:0};\n"
            + "      const safeReplicaRecoverySummary = replicaRecoverySummary || {size:0, totalOutstandingReplicas:0, items:[]};\n"
            + "      const safeHotFailedItems = hotFailedItems || {size:0, items:[]};\n"
            + "      const safeRecentOperatorActions = recentOperatorActions || {size:0, items:[]};\n"
            + "      const safeRecentTimeline = recentTimeline || {size:0, items:[]};\n"
            + "      const safeTimelineRiskSummary = timelineRiskSummary || {criticalCount:0, highCount:0, mediumCount:0, lowCount:0, topRiskLevel:'', topRiskScore:0, topFocusReason:'', topPhase:'', topPath:'', topMessage:''};\n"
            + "      overview += '<div class=\"cockpit-grid\">';\n"
            + "      overview += '<div class=\"cockpit-stack\">';\n"
            + "      overview += renderOpsCockpit(safeHealthSummary, safeFailureTrend, safeRecoverySuccessSummary, safeReplicaRecoverySummary, safeHotFailedItems, safeRecentOperatorActions, safeRecentTimeline, safeTimelineRiskSummary);\n"
            + "      overview += renderCockpitFocus(safeHealthSummary, safeHotFailedItems, safeRecentOperatorActions, safeTimelineRiskSummary);\n"
            + "      overview += '</div>';\n"
            + "      overview += '<div class=\"cockpit-stack\">';\n"
            + "      overview += renderQuickActions(safeReplicaRecoverySummary, safeTimelineRiskSummary, safeHotFailedItems);\n"
            + "      overview += renderQueueMatrix(queueMatrix || {size:0, items:[]});\n"
            + "      overview += '</div>';\n"
            + "      overview += '</div>';\n"
            + "      overview += renderHealthSummary(safeHealthSummary);\n"
            + "      overview += renderFailureTrend(safeFailureTrend);\n"
            + "      overview += renderRecoverySuccessSummary(safeRecoverySuccessSummary);\n"
            + "      overview += renderUploadPolicy(uploadPolicy || {mode:'AUTO_SEGMENT_RESUMABLE', uploadBlockSizeBytes:0, resumeSupported:true, historyRetention:'memory_recent'});\n"
            + "      overview += renderRetryPolicy(retryPolicy || {autoRetryMode:'LIMITED_WITH_BACKOFF', maxRetryCount:0, retryBackoffMillis:0, manualRetryUnrestricted:true});\n"
            + "      let failed = '';\n"
            + "      failed += renderFailureActionFlow(replicaFailureCategorySummary || {size:0, totalOutstandingReplicas:0, items:[]}, hotFailedItems || {size:0, items:[]}, recentOperatorActions || {size:0, items:[]}, safeTimelineRiskSummary, window.lastBatchResult || null);\n"
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
            + "      upload += renderResumedReplicaHotspots(resumedReplicaHotspots || {size:0, items:[]});\n"
            + "      upload += renderStalledReplicaHotspots(stalledReplicaHotspots || {size:0, items:[]});\n"
            + "      upload += renderStalledUploads(stalledUploads || {size:0, items:[]});\n"
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
            + "      const scrollBtn = e.target.closest('button[data-scroll]');\n"
            + "      if(scrollBtn){\n"
            + "        const id = scrollBtn.getAttribute('data-scroll');\n"
            + "        const el = document.getElementById(id);\n"
            + "        if(el && el.scrollIntoView){ el.scrollIntoView({behavior:'smooth', block:'start'}); }\n"
            + "        return;\n"
            + "      }\n"
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
        private int stalledUploadCount;
        private long maxStalledMillis;
        private String topStalledPath = "";
        private String topStalledReplicaLabel = "";
        private final Map<String, Integer> stalledReplicaCounts = new LinkedHashMap<String, Integer>();
        private String stalledReplicaSummary = "";
        private String stalledRecommendedAction = "";
        private String stalledOperatorHint = "";
        private int resumedUploadCount;
        private int resumedSegmentCount;
        private String topResumedPath = "";
        private String topResumedReplicaLabel = "";
        private int topResumedSegments;
        private final Map<String, Integer> resumedReplicaSegments = new LinkedHashMap<String, Integer>();
        private String resumedReplicaSummary = "";

        private void recordStalledReplica(String replicaLabel) {
            String normalized = normalizedReplicaLabel(replicaLabel);
            Integer count = stalledReplicaCounts.get(normalized);
            stalledReplicaCounts.put(normalized, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
        }

        private void finishReplicaSummary() {
            if (stalledReplicaCounts.isEmpty()) {
                stalledReplicaSummary = "";
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> entry : stalledReplicaCounts.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append('=').append(entry.getValue());
            }
            stalledReplicaSummary = sb.toString();
        }

        private void finishStalledAdvice() {
            if (stalledUploadCount <= 0) {
                stalledRecommendedAction = "";
                stalledOperatorHint = "";
                return;
            }
            stalledRecommendedAction = recommendedActionForStalledUpload(topStalledReplicaLabel);
            stalledOperatorHint = operatorHintForStalledUpload(topStalledPath, topStalledReplicaLabel);
        }

        private void recordResumedReplica(String replicaLabel, int resumedSegments) {
            String normalized = normalizedReplicaLabel(replicaLabel);
            Integer count = resumedReplicaSegments.get(normalized);
            resumedReplicaSegments.put(normalized, Integer.valueOf((count == null ? 0 : count.intValue()) + Math.max(0, resumedSegments)));
        }

        private void finishResumedSummary() {
            if (resumedReplicaSegments.isEmpty()) {
                resumedReplicaSummary = "";
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> entry : resumedReplicaSegments.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append('=').append(entry.getValue());
            }
            resumedReplicaSummary = sb.toString();
        }
    }

    private static final class StalledReplicaHotspot {
        private final String replicaLabel;
        private int count;
        private long maxStalledMillis;
        private String topPath = "";

        private StalledReplicaHotspot(String replicaLabel) {
            this.replicaLabel = replicaLabel;
        }

        private void record(String path, long stalledMillis) {
            count++;
            if (stalledMillis > maxStalledMillis) {
                maxStalledMillis = stalledMillis;
                topPath = path == null ? "" : path;
            }
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("replicaLabel", replicaLabel);
            out.put("count", Integer.valueOf(count));
            out.put("maxStalledMillis", Long.valueOf(maxStalledMillis));
            out.put("topPath", topPath);
            out.put("recommendedAction", recommendedActionForStalledUpload(replicaLabel));
            out.put("operatorHint", operatorHintForStalledUpload(topPath, replicaLabel));
            return out;
        }
    }

    private static final class ResumedReplicaHotspot {
        private final String replicaLabel;
        private int count;
        private int resumedSegments;
        private int topResumedSegments;
        private String topPath = "";

        private ResumedReplicaHotspot(String replicaLabel) {
            this.replicaLabel = replicaLabel;
        }

        private void record(String path, int uploadResumedSegments) {
            count++;
            resumedSegments += Math.max(0, uploadResumedSegments);
            if (uploadResumedSegments > topResumedSegments) {
                topResumedSegments = uploadResumedSegments;
                topPath = path == null ? "" : path;
            }
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("replicaLabel", replicaLabel);
            out.put("count", Integer.valueOf(count));
            out.put("resumedSegments", Integer.valueOf(resumedSegments));
            out.put("topResumedSegments", Integer.valueOf(topResumedSegments));
            out.put("topPath", topPath);
            return out;
        }
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
