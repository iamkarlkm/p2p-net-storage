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

import javax.net.p2p.filesync.config.P2PSyncConfig;
import javax.net.p2p.filesync.sync.P2PDirectorySyncService;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.cluster.stats.DbStatsRegistry;
import com.q3lives.ds.core.DsMemory;

/**
 * P55 T4（p2p-sync 端点侧）：{@code /sync/api/db-stats} 3 TR 0F0E。
 *
 * <p>
 * 与 {@code ds.P55_T4_ReplicaLagTruthTest}（p2p-db 侧 2 TR）合计 T4 = 5 TR（spec §5.2）。
 * <ul>
 * <li>TR-41：服务器启动后 {@code GET /sync/api/db-stats} 可连通（HTTP 2xx + 非空响应）；</li>
 * <li>TR-42：端点 HTTP 200 + 外层 {@code ok=true} + {@code stats} 含五组键，且已注册源的真值透传；</li>
 * <li>TR-43：未注册任何源 → {@code ok=true} + 全 0 快照（R8：字段恒存在、HTTP 恒 200，绝不 404/500）。</li>
 * </ul>
 *
 * <p>
 * 0 新线程 / 0 新依赖：仅复用既有 {@link P2PSyncMonitorServer}（NG-3 / R10）。
 */
public class P55_T4_DbStatsEndpointTest {

    @After
    public void tearDown() {
        DbStatsRegistry.clear();
    }

    @Test
    public void tr41_contextRegisteredAndReachable() throws Exception {
        P2PSyncConfig cfg = config("reach");
        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, null);
             P2PSyncMonitorServer server = new P2PSyncMonitorServer(svc, new InetSocketAddress("127.0.0.1", 0))) {
            svc.start();
            server.start();

            String json = send("GET", "http://127.0.0.1:" + server.getPort() + "/sync/api/db-stats", null);
            Assert.assertNotNull(json);
            Assert.assertFalse("响应体不得为空", json.isEmpty());
        }
    }

    @Test
    public void tr42_endpointReturnsOkAndFiveGroupsWithSourceTruth() throws Exception {
        P2PSyncConfig cfg = config("five");
        DbStatsRegistry.registerCache("p55.cache", () -> cache(2, 4096L, 3L, 1L));
        DbStatsRegistry.registerReplicator("p55.replica", () -> 7L);

        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, null);
             P2PSyncMonitorServer server = new P2PSyncMonitorServer(svc, new InetSocketAddress("127.0.0.1", 0))) {
            svc.start();
            server.start();

            String json = send("GET", "http://127.0.0.1:" + server.getPort() + "/sync/api/db-stats", null);
            Assert.assertTrue(json.contains("\"ok\":true"));
            Assert.assertTrue(json.contains("\"stats\""));
            Assert.assertTrue(json.contains("\"cache\""));
            Assert.assertTrue(json.contains("\"wal\""));
            Assert.assertTrue(json.contains("\"txn\""));
            Assert.assertTrue(json.contains("\"compaction\""));
            Assert.assertTrue(json.contains("\"replica\""));
            // 已注册源真值透传（整数字段，规避浮点格式差异）。
            Assert.assertTrue(json.contains("\"activeBlocks\":2"));
            Assert.assertTrue(json.contains("\"cachedBytes\":4096"));
            Assert.assertTrue(json.contains("\"replayLag\":7"));
            Assert.assertTrue(json.contains("\"lag\":7"));
        }
    }

    @Test
    public void tr43_unregisteredSourcesYieldOkWithZeroSnapshot() throws Exception {
        P2PSyncConfig cfg = config("zero");
        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, null);
             P2PSyncMonitorServer server = new P2PSyncMonitorServer(svc, new InetSocketAddress("127.0.0.1", 0))) {
            svc.start();
            server.start();

            String json = send("GET", "http://127.0.0.1:" + server.getPort() + "/sync/api/db-stats", null);
            Assert.assertTrue(json.contains("\"ok\":true"));
            Assert.assertTrue(json.contains("\"stats\""));
            Assert.assertTrue(json.contains("\"replayLag\":0"));
            Assert.assertTrue(json.contains("\"activeBlocks\":0"));
            Assert.assertTrue(json.contains("\"cachedBytes\":0"));
            Assert.assertTrue(json.contains("\"count\":0"));
            Assert.assertTrue(json.contains("\"runsSucceeded\":0"));
            Assert.assertTrue(json.contains("\"lag\":0"));
        }
    }

    private static P2PSyncConfig config(String tag) throws Exception {
        Path root = Files.createTempDirectory("p55_t4_endpoint_root_" + tag + "_");
        Path state = Files.createTempDirectory("p55_t4_endpoint_state_" + tag + "_");
        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(555L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());
        cfg.setMaxRetryCount(3);
        cfg.setRetryBackoffMillis(2500L);
        return cfg;
    }

    private static DsMemory.CacheStats cache(int activeBlocks, long cachedBytes, long hits, long misses) {
        return new DsMemory.CacheStats(16, 1L << 20, activeBlocks, cachedBytes, 0, activeBlocks,
            0L, 0L, 0L, 0L, 0L, 0L, hits, misses);
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
}
