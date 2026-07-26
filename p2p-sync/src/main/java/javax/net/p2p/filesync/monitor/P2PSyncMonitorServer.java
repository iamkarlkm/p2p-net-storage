package javax.net.p2p.filesync.monitor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;

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
    }

    public void start() {
        server.start();
    }

    int getPort() {
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
            boolean ok = retry ? store.retryFailed(t, directory, fileId) : store.discardFailed(t, directory, fileId);
            writeJson(exchange, 200, ok ? "{\"ok\":true}" : "{\"ok\":false,\"message\":\"not found\"}");
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
        root.put("uploads", uploadsToMap(limit));
        root.put("uploadPolicy", uploadPolicyToMap());
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

    private static Map<String, Object> queuesToMap(P2PSyncStateStore store, int limit) {
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

    private static Map<String, Object> queue(P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", set.size());
        out.put("items", sampleItems(store, set, type, dir, limit, false));
        return out;
    }

    private static Map<String, Object> failedQueue(P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", set.size());
        out.put("items", sampleItems(store, set, type, dir, limit, true));
        return out;
    }

    private static List<Map<String, Object>> sampleItems(P2PSyncStateStore store, PersistentLongQueue set, FileSyncEventType type, boolean dir, int limit, boolean includeReason) {
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
            m.put("retryCount", Integer.valueOf(store.getRetryCount(type, dir, fileId)));
            m.put("failedAtMillis", Long.valueOf(store.getFailedAtMillis(type, dir, fileId)));
            m.put("lastRetriedAtMillis", Long.valueOf(store.getLastRetriedAtMillis(type, dir, fileId)));
            if (includeReason) {
                String reason = store.getFailedReason(type, dir, fileId);
                m.put("reason", reason == null ? "" : reason);
            }
            items.add(m);
            count++;
        }
        return items;
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
            + "    .row{display:flex; gap:16px; flex-wrap:wrap;}\n"
            + "    .card{flex:1 1 420px; border:1px solid #ddd; padding:12px;}\n"
            + "    .btn{padding:4px 8px; border:1px solid #666; background:#fff; cursor:pointer;}\n"
            + "  </style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <h2>p2p-sync 队列监控</h2>\n"
            + "  <div>\n"
            + "    <button class=\"btn\" onclick=\"reload()\">刷新</button>\n"
            + "  </div>\n"
            + "  <div id=\"content\"></div>\n"
            + "  <script>\n"
            + "    async function reload(){\n"
            + "      const res = await fetch('/sync/api/queues?limit=200');\n"
            + "      const data = await res.json();\n"
            + "      if(!data.ok){document.getElementById('content').innerText = data.message || 'error';return;}\n"
            + "      render(data.queues, data.uploads, data.uploadPolicy, data.recentCompletedUploads, data.recentFailedUploads);\n"
            + "    }\n"
            + "    function esc(s){return (s||'').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;');}\n"
            + "    function escAttr(s){return esc(s).replaceAll('\"','&quot;').replaceAll(\"'\",'&#39;');}\n"
            + "    function renderQueue(title, q){\n"
            + "      let html = '<div class=\"card\"><h3>'+esc(title)+' (size='+q.size+')</h3>';\n"
            + "      html += '<table><tr><th>fileId</th><th>dir</th><th>type</th><th>path</th><th>retryCount</th><th>failedAtMillis</th><th>lastRetriedAtMillis</th><th>reason</th><th>action</th></tr>';\n"
            + "      for(const it of q.items){\n"
            + "        const reason = it.reason ? esc(it.reason) : '';\n"
            + "        let action = '';\n"
            + "        if(reason){\n"
            + "          action = '<button class=\"btn\" data-action=\"retry\" data-file-id=\"'+escAttr(it.fileId)+'\" data-dir=\"'+it.dir+'\" data-type=\"'+escAttr(it.type)+'\">重试(覆盖同步)</button> ' +\n"
            + "                   '<button class=\"btn\" data-action=\"discard\" data-file-id=\"'+escAttr(it.fileId)+'\" data-dir=\"'+it.dir+'\" data-type=\"'+escAttr(it.type)+'\">放弃</button>';\n"
            + "        }\n"
            + "        html += '<tr><td>'+it.fileId+'</td><td>'+it.dir+'</td><td>'+esc(it.type)+'</td><td>'+esc(it.path)+'</td><td>'+it.retryCount+'</td><td>'+it.failedAtMillis+'</td><td>'+it.lastRetriedAtMillis+'</td><td>'+reason+'</td><td>'+action+'</td></tr>';\n"
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
            + "    function renderUploadPolicy(p){\n"
            + "      let html = '<div class=\"card\"><h3>上传策略</h3>';\n"
            + "      html += '<table><tr><th>mode</th><th>uploadBlockSizeBytes</th><th>resumeSupported</th><th>historyRetention</th></tr>';\n"
            + "      html += '<tr><td>'+esc(p.mode)+'</td><td>'+p.uploadBlockSizeBytes+'</td><td>'+p.resumeSupported+'</td><td>'+esc(p.historyRetention)+'</td></tr>';\n"
            + "      html += '</table></div>';\n"
            + "      return html;\n"
            + "    }\n"
            + "    async function retryIt(fileId, dir, type){\n"
            + "      await fetch('/sync/api/failed/retry?fileId='+fileId+'&dir='+dir+'&type='+encodeURIComponent(type), {method:'POST'});\n"
            + "      await reload();\n"
            + "    }\n"
            + "    async function discardIt(fileId, dir, type){\n"
            + "      await fetch('/sync/api/failed/discard?fileId='+fileId+'&dir='+dir+'&type='+encodeURIComponent(type), {method:'POST'});\n"
            + "      await reload();\n"
            + "    }\n"
            + "    function render(queues, uploads, uploadPolicy, recentCompletedUploads, recentFailedUploads){\n"
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
            + "      let html = '<div class=\"row\">';\n"
            + "      html += renderUploadPolicy(uploadPolicy || {mode:'AUTO_SEGMENT_RESUMABLE', uploadBlockSizeBytes:0, resumeSupported:true, historyRetention:'memory_recent'});\n"
            + "      html += renderUploads(uploads || {size:0, items:[]});\n"
            + "      html += renderUploadHistory('最近完成上传', recentCompletedUploads || {size:0, items:[]});\n"
            + "      html += renderUploadHistory('最近失败上传', recentFailedUploads || {size:0, items:[]});\n"
            + "      for(const [title,key] of keys){\n"
            + "        html += renderQueue(title, queues[key]);\n"
            + "      }\n"
            + "      html += '</div>';\n"
            + "      document.getElementById('content').innerHTML = html;\n"
            + "    }\n"
            + "    document.addEventListener('click', async function(e){\n"
            + "      const btn = e.target.closest('button[data-action]');\n"
            + "      if(!btn){return;}\n"
            + "      const fileId = btn.getAttribute('data-file-id');\n"
            + "      const dir = btn.getAttribute('data-dir');\n"
            + "      const type = btn.getAttribute('data-type');\n"
            + "      if(btn.getAttribute('data-action') === 'retry'){\n"
            + "        await retryIt(fileId, dir, type);\n"
            + "      } else if(btn.getAttribute('data-action') === 'discard'){\n"
            + "        await discardIt(fileId, dir, type);\n"
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
}
