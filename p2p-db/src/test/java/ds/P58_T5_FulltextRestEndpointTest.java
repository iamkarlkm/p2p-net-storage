package ds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.FulltextStore;

import javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry;
import javax.net.p2p.server.rest.FulltextQueryHttpServer;

/**
 * P58 T5：全文检索服务化查询端点（REST/HTTP）4 TR。
 *
 * <p>TR-1 IAE 校验：构造参数越界/非法必须快速失败。</p>
 * <p>TR-2 主路径 + 异常：putDoc/search/searchVector 主路径可达；缺参/非法参返回 400；非 GET 返回 405。</p>
 * <p>TR-3 幂等与兼容性：重复相同搜索响应逐字节一致；重复索引同文档结果稳定；既有 API 签名 0 改。</p>
 * <p>TR-4 ZeroLeak 审计：fulltext/database 根包顶级类数守恒；新增源无禁用依赖 / 无 TODO-FIXME-HACK。</p>
 */
public class P58_T5_FulltextRestEndpointTest {

    private static final String REST_SRC = "src/main/java/javax/net/p2p/server/rest";
    private static final String FT = "src/main/java/com/q3lives/ds/database/fulltext";
    private static final String DB_SRC = "src/main/java/com/q3lives/ds/database";

    private FulltextQueryHttpServer server;
    private Path clusterRoot;
    private HttpClient http;
    private String base;

    @Before
    public void setUp() throws Exception {
        clusterRoot = Files.createTempDirectory("p58t5-rest");
        server = new FulltextQueryHttpServer(0, 1, "rest_shard");
        server.start();
        base = "http://127.0.0.1:" + server.port();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
        DsServerFulltextRegistry.clearAll();
        if (clusterRoot != null) {
            deleteRecursive(clusterRoot.toFile());
        }
    }

    // ============== TR-1 IAE 校验 ==============
    @Test(timeout = 60000)
    public void tr1_constructorValidation() throws Exception {
        expectIae(() -> new FulltextQueryHttpServer(-1, 1, "p"));
        expectIae(() -> new FulltextQueryHttpServer(70000, 1, "p"));
        expectIae(() -> new FulltextQueryHttpServer(0, 0, "p"));
        expectIae(() -> new FulltextQueryHttpServer(0, -3, "p"));
        expectIae(() -> new FulltextQueryHttpServer(0, 1, ""));
        expectIae(() -> new FulltextQueryHttpServer(0, 1, null));
    }

    // ============== TR-2 主路径 + 异常 ==============
    @Test(timeout = 120000)
    public void tr2_mainPathAndErrors() throws Exception {
        // 主路径：索引两篇文档
        HttpResponse<String> p1 = get("/ft/putDoc?clusterRoot=" + enc(clusterRoot) + "&docId=1&text=" + enc("alpha beta gamma"));
        assertEquals(200, p1.statusCode());
        assertTrue(p1.body(), p1.body().contains("\"ok\":true"));
        assertTrue(p1.body(), p1.body().contains("\"docId\":1"));

        HttpResponse<String> p2 = get("/ft/putDoc?clusterRoot=" + enc(clusterRoot) + "&docId=2&text=" + enc("beta delta"));
        assertEquals(200, p2.statusCode());

        // 文本检索主路径
        HttpResponse<String> s = get("/ft/search?clusterRoot=" + enc(clusterRoot) + "&shardId=0&query=beta&k=10");
        assertEquals(200, s.statusCode());
        assertTrue(s.body(), s.body().contains("\"ok\":true"));
        assertTrue("应命中文档 1: " + s.body(), s.body().contains("\"docId\":1"));
        assertTrue("应命中文档 2: " + s.body(), s.body().contains("\"docId\":2"));

        // 向量检索端点可用（无向量索引时返回空 hits，仍须 200/ok）
        HttpResponse<String> v = get("/ft/searchVector?clusterRoot=" + enc(clusterRoot) + "&shardId=0&vec=1,0,0&k=5");
        assertEquals(200, v.statusCode());
        assertTrue(v.body(), v.body().contains("\"ok\":true"));
        assertTrue(v.body(), v.body().contains("\"hits\":["));

        // 健康探针
        HttpResponse<String> h = get("/ft/health");
        assertEquals(200, h.statusCode());
        assertTrue(h.body(), h.body().contains("\"ok\":true"));

        // 异常：缺 query
        HttpResponse<String> noQuery = get("/ft/search?clusterRoot=" + enc(clusterRoot) + "&k=5");
        assertEquals(400, noQuery.statusCode());
        assertTrue(noQuery.body(), noQuery.body().contains("\"ok\":false"));

        // 异常：k 非正
        HttpResponse<String> badK = get("/ft/search?clusterRoot=" + enc(clusterRoot) + "&query=beta&k=0");
        assertEquals(400, badK.statusCode());

        // 异常：vec 非法
        HttpResponse<String> badVec = get("/ft/searchVector?clusterRoot=" + enc(clusterRoot) + "&vec=abc");
        assertEquals(400, badVec.statusCode());

        // 异常：docId 非法
        HttpResponse<String> badDoc = get("/ft/putDoc?clusterRoot=" + enc(clusterRoot) + "&docId=xyz&text=x");
        assertEquals(400, badDoc.statusCode());

        // 非 GET → 405
        HttpResponse<String> post = post("/ft/search?clusterRoot=" + enc(clusterRoot) + "&query=beta");
        assertEquals(405, post.statusCode());
    }

    // ============== TR-3 幂等与兼容性 ==============
    @Test(timeout = 120000)
    public void tr3_idempotentAndCompatible() throws Exception {
        assertEquals(200, get("/ft/putDoc?clusterRoot=" + enc(clusterRoot) + "&docId=7&text=" + enc("echo foxtrot golf")).statusCode());

        String u = "/ft/search?clusterRoot=" + enc(clusterRoot) + "&shardId=0&query=foxtrot&k=10";
        String a = get(u).body();
        String b = get(u).body();
        assertEquals("重复相同搜索必须逐字节一致", a, b);
        assertTrue(a, a.contains("\"docId\":7"));

        // 重复索引同文档 → 结果稳定（不重复计入）
        assertEquals(200, get("/ft/putDoc?clusterRoot=" + enc(clusterRoot) + "&docId=7&text=" + enc("echo foxtrot golf")).statusCode());
        String c = get(u).body();
        assertEquals("重复索引后结果必须稳定", a, c);

        // 既有 API 签名 0 改
        assertNotNull("ShardRouter.search 原签名必须保留",
                FulltextStore.ShardRouter.class.getMethod("search", int.class, String.class, int.class,
                        FulltextStore.SearchOptions.class));
        assertNotNull("ShardRouter.searchWithVector 原签名必须保留",
                FulltextStore.ShardRouter.class.getMethod("searchWithVector", int.class, float[].class, int.class));
        assertNotNull("FulltextStore.ingestDoc 原签名必须保留",
                FulltextStore.class.getDeclaredMethod("ingestDoc", long.class, String.class));
    }

    // ============== TR-4 ZeroLeak 审计 ==============
    @Test(timeout = 60000)
    public void tr4_zeroLeakAudit() throws Exception {
        assertEquals("fulltext 根包顶级类数必须恒 = 3", 3, countTopLevelJava(FT));
        // 基线 11→12：HanLpProvider（REV7 HanLP 硬依赖分词 Provider）落于 database 根包，故上调为 12 并冻结。
        assertEquals("database 根包顶级类数必须守恒（0 新增）", 12, countTopLevelJava(DB_SRC));
        assertEquals("新包 server/rest/ 仅 1 个顶级类", 1, countTopLevelJava(REST_SRC));

        File src = new File(System.getProperty("user.dir"),
                REST_SRC + "/FulltextQueryHttpServer.java");
        assertTrue("新增源文件必须存在: " + src.getAbsolutePath(), src.isFile());
        String code = new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8);
        for (String banned : new String[]{"io.netty", "io.grpc", "okhttp", "jetty", "springframework"}) {
            assertTrue("REST 端点不得引入 " + banned, !code.contains(banned));
        }
        for (String banned : new String[]{"TODO", "FIXME", "HACK"}) {
            assertTrue("found " + banned + " in FulltextQueryHttpServer.java", !code.contains(banned));
        }
    }

    // ============== helpers ==============
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void expectIae(ThrowingRunnable r) {
        try {
            r.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        } catch (Exception other) {
            fail("expected IllegalArgumentException but got " + other);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("")).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(Path p) {
        return enc(p.toString());
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static int countTopLevelJava(String rel) {
        File dir = new File(System.getProperty("user.dir"), rel);
        assertTrue("源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
        File[] children = dir.listFiles();
        int n = 0;
        if (children != null) {
            for (File f : children) {
                if (f.isFile() && f.getName().endsWith(".java")) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
