package javax.net.p2p.server.rest;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry;

/**
 * 全文检索服务化查询端点（REST/HTTP）。
 *
 * <p>零外部依赖：基于 JDK 内置 {@code com.sun.net.httpserver}，复用
 * {@link DsServerFulltextRegistry} 的 {@link FulltextStore.ShardRouter} 缓存，避免重复打开分片。</p>
 *
 * <p>端点（GET，查询参数）：</p>
 * <ul>
 *   <li>{@code /ft/health} —— 存活探针</li>
 *   <li>{@code /ft/putDoc?clusterRoot=&docId=&text=} —— 索引单篇文档</li>
 *   <li>{@code /ft/search?clusterRoot=&shardId=&query=&k=} —— 文本检索</li>
 *   <li>{@code /ft/searchVector?clusterRoot=&shardId=&vec=&k=} —— 向量检索（vec 为逗号分隔 float）</li>
 * </ul>
 */
public final class FulltextQueryHttpServer implements AutoCloseable {

    private static final String DEFAULT_CLUSTER_ROOT = "./ft-rest-default";

    private final HttpServer server;
    private final int shardCount;
    private final String shardPrefix;
    private final ExecutorService executor;

    /**
     * @param port       监听端口，0 表示由系统分配临时端口
     * @param shardCount 默认分片数（clusterRoot 未显式指定 sc 时生效），必须 &gt; 0
     * @param shardPrefix 分片目录前缀，必须非空
     * @throws IOException 绑定失败
     */
    public FulltextQueryHttpServer(int port, int shardCount, String shardPrefix) throws IOException {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be > 0: " + shardCount);
        }
        if (shardPrefix == null || shardPrefix.isBlank()) {
            throw new IllegalArgumentException("shardPrefix must not be blank");
        }
        this.shardCount = shardCount;
        this.shardPrefix = shardPrefix;
        this.executor = Executors.newFixedThreadPool(4);
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/ft/health", this::guardedHealth);
        this.server.createContext("/ft/putDoc", this::guardedPutDoc);
        this.server.createContext("/ft/search", this::guardedSearch);
        this.server.createContext("/ft/searchVector", this::guardedSearchVector);
        this.server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    /** 实际绑定端口（构造时传 0 则返回系统分配的端口）。 */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ================== 端点 ==================

    private void guardedHealth(HttpExchange e) throws IOException {
        long t0 = System.nanoTime();
        try {
            handleHealth(e);
        } catch (Exception ex) {
            sendError(e, 500, ex);
        } finally {
            e.getResponseHeaders().set("X-Elapsed-Nanos", Long.toString(System.nanoTime() - t0));
            e.close();
        }
    }

    private void guardedPutDoc(HttpExchange e) throws IOException {
        try {
            handlePutDoc(e);
        } catch (Exception ex) {
            sendError(e, 500, ex);
        } finally {
            e.close();
        }
    }

    private void guardedSearch(HttpExchange e) throws IOException {
        try {
            handleSearch(e);
        } catch (Exception ex) {
            sendError(e, 500, ex);
        } finally {
            e.close();
        }
    }

    private void guardedSearchVector(HttpExchange e) throws IOException {
        try {
            handleSearchVector(e);
        } catch (Exception ex) {
            sendError(e, 500, ex);
        } finally {
            e.close();
        }
    }

    private void handleHealth(HttpExchange e) throws IOException {
        if (!requireGet(e)) {
            return;
        }
        writeJson(e, 200, "{\"ok\":true,\"shardCount\":" + shardCount + ",\"shardPrefix\":\"" + esc(shardPrefix) + "\"}");
    }

    private void handlePutDoc(HttpExchange e) throws IOException {
        if (!requireGet(e)) {
            return;
        }
        String docIdRaw = queryParam(e, "docId", null);
        String text = queryParam(e, "text", null);
        if (docIdRaw == null || docIdRaw.isBlank()) {
            writeJson(e, 400, error("docId required"));
            return;
        }
        long docId;
        try {
            docId = Long.parseLong(docIdRaw.trim());
        } catch (NumberFormatException nfe) {
            writeJson(e, 400, error("docId must be a long: " + docIdRaw));
            return;
        }
        if (text == null) {
            writeJson(e, 400, error("text required"));
            return;
        }
        FulltextStore.ShardRouter router = router(e);
        FulltextStore.IngestStat stat = router.putDocCluster(docId, text);
        writeJson(e, 200, "{\"ok\":true,\"docId\":" + docId
                + ",\"pairs\":\"" + (stat == null ? 0L : stat.pairsTotal) + "\"}");
    }

    private void handleSearch(HttpExchange e) throws IOException {
        if (!requireGet(e)) {
            return;
        }
        String query = queryParam(e, "query", null);
        if (query == null || query.isBlank()) {
            writeJson(e, 400, error("query required"));
            return;
        }
        int shardId = intParam(e, "shardId", 0);
        Integer k = positiveIntParam(e, "k", 10);
        if (k == null) {
            return;
        }
        FulltextStore.ShardRouter router = router(e);
        List<FulltextStore.ScoredDocHit> hits =
                router.search(shardId, query, k, FulltextStore.SearchOptions.defaults());
        writeJson(e, 200, hitsJson(hits));
    }

    private void handleSearchVector(HttpExchange e) throws IOException {
        if (!requireGet(e)) {
            return;
        }
        String vecRaw = queryParam(e, "vec", null);
        if (vecRaw == null || vecRaw.isBlank()) {
            writeJson(e, 400, error("vec required"));
            return;
        }
        float[] vec;
        try {
            vec = parseVec(vecRaw);
        } catch (IllegalArgumentException iae) {
            writeJson(e, 400, error(iae.getMessage()));
            return;
        }
        int shardId = intParam(e, "shardId", 0);
        Integer k = positiveIntParam(e, "k", 10);
        if (k == null) {
            return;
        }
        FulltextStore.ShardRouter router = router(e);
        List<FulltextStore.ScoredDocHit> hits = router.searchWithVector(shardId, vec, k);
        writeJson(e, 200, hitsJson(hits));
    }

    // ================== 解析/校验 ==================

    private boolean requireGet(HttpExchange e) throws IOException {
        if (!"GET".equals(e.getRequestMethod())) {
            e.getResponseHeaders().set("Allow", "GET");
            writeJson(e, 405, error("Method Not Allowed"));
            return false;
        }
        return true;
    }

    /** 解析 k，缺失取默认值；非正整数写 400 并返回 null。 */
    private Integer positiveIntParam(HttpExchange e, String key, int def) throws IOException {
        String raw = queryParam(e, key, null);
        if (raw == null || raw.isBlank()) {
            return def;
        }
        int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException nfe) {
            writeJson(e, 400, error(key + " must be an int: " + raw));
            return null;
        }
        if (v <= 0) {
            writeJson(e, 400, error(key + " must be > 0: " + v));
            return null;
        }
        return v;
    }

    private int intParam(HttpExchange e, String key, int def) {
        String raw = queryParam(e, key, null);
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException nfe) {
            return def;
        }
    }

    private static float[] parseVec(String raw) {
        String[] parts = raw.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i].trim();
            try {
                out[i] = Float.parseFloat(s);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("vec[" + i + "] not a float: " + s);
            }
        }
        return out;
    }

    private FulltextStore.ShardRouter router(HttpExchange e) throws IOException {
        String root = queryParam(e, "clusterRoot", DEFAULT_CLUSTER_ROOT);
        if (root == null || root.isBlank()) {
            root = DEFAULT_CLUSTER_ROOT;
        }
        return DsServerFulltextRegistry.getOrCreate(root, shardCount, shardPrefix);
    }

    private static String queryParam(HttpExchange e, String key, String def) {
        String q = e.getRequestURI().getRawQuery();
        if (q == null || q.isBlank()) {
            return def;
        }
        for (String part : q.split("&")) {
            int idx = part.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            if (!part.substring(0, idx).equals(key)) {
                continue;
            }
            String v = part.substring(idx + 1);
            try {
                return URLDecoder.decode(v, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return v;
            }
        }
        return def;
    }

    // ================== 输出 ==================

    private static String hitsJson(List<FulltextStore.ScoredDocHit> hits) {
        StringBuilder sb = new StringBuilder(64 + hits.size() * 96);
        sb.append("{\"ok\":true,\"count\":").append(hits.size()).append(",\"hits\":[");
        for (int i = 0; i < hits.size(); i++) {
            FulltextStore.ScoredDocHit h = hits.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"docId\":").append(h.docId)
                    .append(",\"parentId\":").append(h.parentId)
                    .append(",\"level\":").append(h.level)
                    .append(",\"seq\":").append(h.seq)
                    .append(",\"unitId\":").append(h.unitId)
                    .append(",\"finalScore\":").append(h.finalScore)
                    .append(",\"sortScore\":").append(h.sortScore)
                    .append(",\"rrfScore\":").append(h.rrfScore)
                    .append(",\"phraseBoostScore\":").append(h.phraseBoostScore)
                    .append(",\"matchedQueryTerms\":").append(h.matchedQueryTerms)
                    .append(",\"vectorScore\":").append(h.vectorScore)
                    .append(",\"shardId\":").append(h.shardId)
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void sendError(HttpExchange e, int status, Exception ex) {
        try {
            writeJson(e, status, error(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        } catch (IOException ignore) {
            // 响应体已关闭
        }
    }

    private static void writeJson(HttpExchange e, int status, String json) throws IOException {
        Headers h = e.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Access-Control-Allow-Origin", "*");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        e.sendResponseHeaders(status, body.length);
        try (OutputStream os = e.getResponseBody()) {
            os.write(body);
        }
    }

    private static String error(String message) {
        return "{\"ok\":false,\"error\":\"" + esc(message) + "\"}";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
