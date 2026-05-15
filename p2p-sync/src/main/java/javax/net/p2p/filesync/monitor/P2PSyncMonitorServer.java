package javax.net.p2p.filesync.monitor;

import com.q3lives.ds.collections.DsHashSet;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
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
import java.util.concurrent.Executors;
import javax.net.p2p.filesync.sync.FileSyncEventType;
import javax.net.p2p.filesync.sync.P2PDirectorySyncService;
import javax.net.p2p.filesync.sync.P2PSyncStateStore;

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

    private static String buildQueuesJson(P2PSyncStateStore store, int limit) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ok", Boolean.TRUE);
        root.put("queues", queuesToMap(store, limit));
        return toJson(root);
    }

    private static Map<String, Object> queuesToMap(P2PSyncStateStore store, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file_create", queue(store, store.fileCreatesActive(), FileSyncEventType.CREATE, false, limit));
        out.put("file_modify", queue(store, store.fileModifiesActive(), FileSyncEventType.MODIFY, false, limit));
        out.put("file_delete", queue(store, store.fileDeletesActive(), FileSyncEventType.DELETE, false, limit));
        out.put("dir_create", queue(store, store.dirCreatesActive(), FileSyncEventType.CREATE, true, limit));
        out.put("dir_delete", queue(store, store.dirDeletesActive(), FileSyncEventType.DELETE, true, limit));
        out.put("failed_file_create", failedQueue(store, store.fileCreatesFailed(), FileSyncEventType.CREATE, false, limit));
        out.put("failed_file_modify", failedQueue(store, store.fileModifiesFailed(), FileSyncEventType.MODIFY, false, limit));
        out.put("failed_file_delete", failedQueue(store, store.fileDeletesFailed(), FileSyncEventType.DELETE, false, limit));
        out.put("failed_dir_create", failedQueue(store, store.dirCreatesFailed(), FileSyncEventType.CREATE, true, limit));
        out.put("failed_dir_delete", failedQueue(store, store.dirDeletesFailed(), FileSyncEventType.DELETE, true, limit));
        return out;
    }

    private static Map<String, Object> queue(P2PSyncStateStore store, DsHashSet set, FileSyncEventType type, boolean dir, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", set.size());
        out.put("items", sampleItems(store, set, type, dir, limit, false));
        return out;
    }

    private static Map<String, Object> failedQueue(P2PSyncStateStore store, DsHashSet set, FileSyncEventType type, boolean dir, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", set.size());
        out.put("items", sampleItems(store, set, type, dir, limit, true));
        return out;
    }

    private static List<Map<String, Object>> sampleItems(P2PSyncStateStore store, DsHashSet set, FileSyncEventType type, boolean dir, int limit, boolean includeReason) {
        List<Map<String, Object>> items = new ArrayList<>();
        int count = 0;
        for (Object o : set) {
            if (count >= limit) {
                break;
            }
            long fileId = ((Long) o).longValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fileId", fileId);
            m.put("dir", dir);
            m.put("type", type.name());
            String path = store.getRelativePath(fileId);
            m.put("path", path == null ? "" : path);
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
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1"/>
              <title>p2p-sync monitor</title>
              <style>
                body{font-family:system-ui,Arial; margin:16px;}
                table{border-collapse:collapse; width:100%; margin:12px 0;}
                th,td{border:1px solid #ddd; padding:6px 8px; font-size:12px;}
                th{background:#f6f6f6; text-align:left;}
                .row{display:flex; gap:16px; flex-wrap:wrap;}
                .card{flex:1 1 420px; border:1px solid #ddd; padding:12px;}
                .btn{padding:4px 8px; border:1px solid #666; background:#fff; cursor:pointer;}
              </style>
            </head>
            <body>
              <h2>p2p-sync 队列监控</h2>
              <div>
                <button class="btn" onclick="reload()">刷新</button>
              </div>
              <div id="content"></div>
              <script>
                async function reload(){
                  const res = await fetch('/sync/api/queues?limit=200');
                  const data = await res.json();
                  if(!data.ok){document.getElementById('content').innerText = data.message || 'error';return;}
                  render(data.queues);
                }
                function esc(s){return (s||'').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;');}
                function renderQueue(title, q){
                  let html = '<div class="card"><h3>'+esc(title)+' (size='+q.size+')</h3>';
                  html += '<table><tr><th>fileId</th><th>dir</th><th>type</th><th>path</th><th>reason</th><th>action</th></tr>';
                  for(const it of q.items){
                    const reason = it.reason ? esc(it.reason) : '';
                    let action = '';
                    if(reason){
                      action = '<button class="btn" onclick="retryIt('+it.fileId+','+it.dir+',\\''+it.type+'\\')">重试(覆盖同步)</button> ' +
                               '<button class="btn" onclick="discardIt('+it.fileId+','+it.dir+',\\''+it.type+'\\')">放弃</button>';
                    }
                    html += '<tr><td>'+it.fileId+'</td><td>'+it.dir+'</td><td>'+esc(it.type)+'</td><td>'+esc(it.path)+'</td><td>'+reason+'</td><td>'+action+'</td></tr>';
                  }
                  html += '</table></div>';
                  return html;
                }
                async function retryIt(fileId, dir, type){
                  await fetch('/sync/api/failed/retry?fileId='+fileId+'&dir='+dir+'&type='+encodeURIComponent(type), {method:'POST'});
                  await reload();
                }
                async function discardIt(fileId, dir, type){
                  await fetch('/sync/api/failed/discard?fileId='+fileId+'&dir='+dir+'&type='+encodeURIComponent(type), {method:'POST'});
                  await reload();
                }
                function render(queues){
                  const keys = [
                    ['新增(文件)', 'file_create'],
                    ['修改(文件)', 'file_modify'],
                    ['删除(文件)', 'file_delete'],
                    ['新增(目录)', 'dir_create'],
                    ['删除(目录)', 'dir_delete'],
                    ['失败-新增(文件)', 'failed_file_create'],
                    ['失败-修改(文件)', 'failed_file_modify'],
                    ['失败-删除(文件)', 'failed_file_delete'],
                    ['失败-新增(目录)', 'failed_dir_create'],
                    ['失败-删除(目录)', 'failed_dir_delete'],
                  ];
                  let html = '<div class="row">';
                  for(const [title,key] of keys){
                    html += renderQueue(title, queues[key]);
                  }
                  html += '</div>';
                  document.getElementById('content').innerHTML = html;
                }
                reload();
              </script>
            </body>
            </html>
            """;
    }

    private static String param(URI uri, String key) {
        String q = uri.getRawQuery();
        if (q == null || q.isBlank()) {
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
        if (v == null || v.isBlank()) {
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
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof Number n) return n.toString();
        if (v instanceof String s) return quote(s);
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(String.valueOf(e.getKey())));
                sb.append(':');
                sb.append(toJson(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (v instanceof List<?> list) {
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
