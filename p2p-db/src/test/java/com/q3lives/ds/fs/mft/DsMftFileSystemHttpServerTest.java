package com.q3lives.ds.fs.mft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DsMftFileSystemHttpServer 集成测试。
 */
class DsMftFileSystemHttpServerTest {

    private static final int TEST_PORT = 19999;

    private Path tempDir;
    private DsMftFileSystem fs;
    private DsMftFileSystemHttpServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("mft_http_test_");
        DsMftFileSystemConfig cfg = new DsMftFileSystemConfig();
        cfg.setNamespaceDir(tempDir.toString());
        cfg.setFsName("httpTestFs");
        fs = DsMftFileSystem.loadOrInit(cfg);
        server = new DsMftFileSystemHttpServer(fs, new InetSocketAddress("127.0.0.1", TEST_PORT));
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.close();
        }
        if (fs != null) {
            fs.close();
        }
        if (tempDir != null) {
            Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + TEST_PORT + path))
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private String postJson(String path, String json) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + TEST_PORT + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private String postBinary(String path, byte[] data) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + TEST_PORT + path))
                .POST(HttpRequest.BodyPublishers.ofByteArray(data))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    // ================== 基础 ==================

    @Test
    void testIndexPage() throws IOException, InterruptedException {
        String html = get("/");
        assertTrue(html.contains("DsMftFileSystem 文件管理器"));
        assertTrue(html.contains("上传文件"));
    }

    @Test
    void testCorsHeaders() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + TEST_PORT + "/api/list?path=/"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, res.statusCode());
        assertNotNull(res.headers().firstValue("Access-Control-Allow-Origin"));
    }

    // ================== 目录列表 ==================

    @Test
    void testListRoot() throws IOException, InterruptedException {
        String json = get("/api/list?path=/");
        assertTrue(json.contains("\"ok\":true"));
        assertTrue(json.contains("\"path\":\"/\""));
    }

    @Test
    void testListNotFound() throws IOException, InterruptedException {
        String json = get("/api/list?path=/notexist");
        assertTrue(json.contains("\"ok\":false"));
    }

    // ================== 上传下载 ==================

    @Test
    void testUploadAndDownload() throws IOException, InterruptedException {
        byte[] data = "Hello Web UI".getBytes(StandardCharsets.UTF_8);
        String up = postBinary("/api/upload?path=/upload_test.txt", data);
        assertTrue(up.contains("\"ok\":true"), "upload should succeed: " + up);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + TEST_PORT + "/api/download?path=/upload_test.txt"))
                .GET()
                .build();
        HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, res.statusCode());
        assertArrayEquals(data, res.body());
    }

    // ================== 新建文件夹 ==================

    @Test
    void testMkdir() throws IOException, InterruptedException {
        String json = postJson("/api/mkdir", "{\"path\":\"/new_dir\"}");
        assertTrue(json.contains("\"ok\":true"), "mkdir should succeed: " + json);

        // 验证目录存在
        String list = get("/api/list?path=/");
        assertTrue(list.contains("new_dir"));
    }

    // ================== 删除 ==================

    @Test
    void testDeleteFile() throws IOException, InterruptedException {
        fs.writeFile("/del_api.txt", "x".getBytes(StandardCharsets.UTF_8));
        String json = postJson("/api/delete", "{\"path\":\"/del_api.txt\"}");
        assertTrue(json.contains("\"ok\":true"), "delete should succeed: " + json);
        assertFalse(fs.exists("/del_api.txt"));
    }

    @Test
    void testDeleteDir() throws IOException, InterruptedException {
        fs.mkdir("/empty_api_dir");
        String json = postJson("/api/delete", "{\"path\":\"/empty_api_dir\"}");
        assertTrue(json.contains("\"ok\":true"), "delete dir should succeed: " + json);
        assertFalse(fs.exists("/empty_api_dir"));
    }

    // ================== 重命名 ==================

    @Test
    void testRenameFile() throws IOException, InterruptedException {
        fs.writeFile("/rename_old.txt", "data".getBytes(StandardCharsets.UTF_8));
        String json = postJson("/api/rename", "{\"path\":\"/rename_old.txt\",\"newPath\":\"/rename_new.txt\"}");
        assertTrue(json.contains("\"ok\":true"), "rename should succeed: " + json);
        assertFalse(fs.exists("/rename_old.txt"));
        assertTrue(fs.exists("/rename_new.txt"));
    }

    // ================== 复制 ==================

    @Test
    void testCopyFile() throws IOException, InterruptedException {
        fs.writeFile("/copy_src.txt", "copy me".getBytes(StandardCharsets.UTF_8));
        String json = postJson("/api/copy", "{\"path\":\"/copy_src.txt\",\"newPath\":\"/copy_dst.txt\"}");
        assertTrue(json.contains("\"ok\":true"), "copy should succeed: " + json);
        assertTrue(fs.exists("/copy_src.txt"));
        assertTrue(fs.exists("/copy_dst.txt"));

        byte[] src = fs.readFile("/copy_src.txt");
        byte[] dst = fs.readFile("/copy_dst.txt");
        assertArrayEquals(src, dst);
    }

    // ================== 移动 ==================

    @Test
    void testMoveFile() throws IOException, InterruptedException {
        fs.writeFile("/move_src.txt", "move me".getBytes(StandardCharsets.UTF_8));
        String json = postJson("/api/move", "{\"path\":\"/move_src.txt\",\"newPath\":\"/move_dst.txt\"}");
        assertTrue(json.contains("\"ok\":true"), "move should succeed: " + json);
        assertFalse(fs.exists("/move_src.txt"));
        assertTrue(fs.exists("/move_dst.txt"));
    }

    // ================== 树形 API ==================

    @Test
    void testTreeApi() throws IOException, InterruptedException {
        fs.mkdir("/tree/a");
        fs.mkdir("/tree/b");
        String json = get("/api/tree?path=/tree");
        assertTrue(json.contains("\"ok\":true"));
        assertTrue(json.contains("a"));
        assertTrue(json.contains("b"));
    }

    // ================== stat ==================

    @Test
    void testStatFile() throws IOException, InterruptedException {
        fs.writeFile("/stat_api.txt", "stat test".getBytes(StandardCharsets.UTF_8));
        String json = get("/api/stat?path=/stat_api.txt");
        assertTrue(json.contains("\"ok\":true"));
        assertTrue(json.contains("\"size\":9"));
        assertTrue(json.contains("\"isFile\":true"));
    }
}
