package com.q3lives.ds.fs.mft;

import com.q3lives.ds.fs.Ds128Inode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * DsMftFileSystem Web UI HTTP 服务器。
 *
 * <p>提供 REST API + 内嵌前端文件管理器界面，支持：</p>
 * <ul>
 *   <li>左侧目录树、右侧文件列表</li>
 *   <li>文件上传下载（上传直接走 binary POST body）</li>
 *   <li>新建文件夹、删除、重命名、移动、复制</li>
 *   <li>面包屑导航、右键菜单</li>
 * </ul>
 */
public final class DsMftFileSystemHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final DsMftFileSystem fs;

    public DsMftFileSystemHttpServer(DsMftFileSystem fs, InetSocketAddress bind) throws IOException {
        this.fs = fs;
        this.server = HttpServer.create(bind, 0);
        this.server.setExecutor(Executors.newFixedThreadPool(8));

        this.server.createContext("/", new CorsRootHandler(new IndexHandler()));
        this.server.createContext("/api/list", new CorsHandler(new ListHandler()));
        this.server.createContext("/api/stat", new CorsHandler(new StatHandler()));
        this.server.createContext("/api/download", new CorsHandler(new DownloadHandler()));
        this.server.createContext("/api/upload", new CorsHandler(new UploadHandler()));
        this.server.createContext("/api/mkdir", new CorsHandler(new MkdirHandler()));
        this.server.createContext("/api/delete", new CorsHandler(new DeleteHandler()));
        this.server.createContext("/api/rename", new CorsHandler(new RenameHandler()));
        this.server.createContext("/api/move", new CorsHandler(new MoveHandler()));
        this.server.createContext("/api/copy", new CorsHandler(new CopyHandler()));
        this.server.createContext("/api/tree", new CorsHandler(new TreeHandler()));
    }

    public void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ================== 跨域包装器 ==================

    private static void addCors(Headers h) {
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private final class CorsRootHandler implements HttpHandler {
        private final HttpHandler delegate;
        CorsRootHandler(HttpHandler delegate) { this.delegate = delegate; }
        @Override public void handle(HttpExchange e) throws IOException {
            addCors(e.getResponseHeaders());
            if ("OPTIONS".equals(e.getRequestMethod())) {
                e.sendResponseHeaders(204, -1);
                return;
            }
            delegate.handle(e);
        }
    }

    private final class CorsHandler implements HttpHandler {
        private final HttpHandler delegate;
        CorsHandler(HttpHandler delegate) { this.delegate = delegate; }
        @Override public void handle(HttpExchange e) throws IOException {
            addCors(e.getResponseHeaders());
            if ("OPTIONS".equals(e.getRequestMethod())) {
                e.sendResponseHeaders(204, -1);
                return;
            }
            try {
                delegate.handle(e);
            } catch (Exception ex) {
                writeJson(e, 500, error(ex.getMessage()));
            }
        }
    }

    // ================== 前端页面 ==================

    private final class IndexHandler implements HttpHandler {
        @Override public void handle(HttpExchange e) throws IOException {
            byte[] body = indexHtml().getBytes(StandardCharsets.UTF_8);
            Headers h = e.getResponseHeaders();
            h.set("Content-Type", "text/html; charset=utf-8");
            e.sendResponseHeaders(200, body.length);
            try (OutputStream os = e.getResponseBody()) { os.write(body); }
        }
    }

    // ================== API 处理器 ==================

    private final class ListHandler implements HttpHandler {
        @Override public void handle(HttpExchange e) throws IOException {
            String path = queryParam(e, "path", "/");
            Ds128Inode inode = fs.stat(path);
            if (inode == null) {
                writeJson(e, 404, error("path not found"));
                return;
            }
            List<Map<String, Object>> items = new ArrayList<>();
            if ((inode.i_mode & 0x4000) != 0) {
                List<DsMftFileSystem.DirEntry> entries = fs.listDir(path);
                for (DsMftFileSystem.DirEntry entry : entries) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", entry.name);
                    m.put("fileId", entry.fileId);
                    m.put("isDirectory", entry.isDirectory);
                    Ds128Inode child = fs.stat(joinPath(path, entry.name));
                    if (child != null) {
                        m.put("size", child.data_size);
                        m.put("mtime", child.data_mtime);
                    }
                    items.add(m);
                }
            }
            Map<String, Object> res = ok();
            res.put("path", path);
            res.put("items", items);
            writeJson(e, 200, toJson(res));
        }
    }

    private final class StatHandler implements HttpHandler {
        @Override public void handle(HttpExchange e) throws IOException {
            String path = queryParam(e, "path", "/");
            Ds128Inode inode = fs.stat(path);
            if (inode == null) {
                writeJson(e, 404, error("path not found"));
                return;
            }
            Map<String, Object> m = ok();
            m.put("path", path);
            m.put("size", inode.data_size);
            m.put("mtime", inode.data_mtime);
            m.put("ctime", inode.data_ctime);
            m.put("mode", inode.i_mode);
            m.put("isDirectory", (inode.i_mode & 0x4000) != 0);
            m.put("isFile", (inode.i_mode & 0x8000) != 0);
            writeJson(e, 200, toJson(m));
        }
    }

    private final class TreeHandler implements HttpHandler {
        @Override public void handle(HttpExchange e) throws IOException {
            String path = queryParam(e, "path", "/");
            List<Map<String, Object>> nodes = new ArrayList<>();
            Ds128Inode inode = fs.stat(path);
            if (inode != null && (inode.i_mode & 0x4000) != 0) {
                List<DsMftFileSystem.DirEntry> entries = fs.listDir(path);
                for (DsMftFileSystem.DirEntry entry : entries) {
                    if (entry.isDirectory) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", entry.name);
                        m.put("path", joinPath(path, entry.name));
                        m.put("hasChildren", hasChildren(joinPath(path, entry.name)));
                        nodes.add(m);
                    }
                }
            }
            Map<String, Object> res = ok();
            res.put("path", path);
            res.put("nodes", nodes);
            writeJson(e, 200, toJson(res));
        }
    }

    private boolean hasChildren(String path) throws IOException {
        Ds128Inode inode = fs.stat(path);
        if (inode == null || (inode.i_mode & 0x4000) == 0) return false;
        List<DsMftFileSystem.DirEntry> entries = fs.listDir(path);
        for (DsMftFileSystem.DirEntry e : entries) {
            if (e.isDirectory) return true;
        }
        return false;
    }

    private final class DownloadHandler implements HttpHandler {
        @Override public void handle(HttpExchange e) throws IOException {
            String path = queryParam(e, "path", "");
            if (path.isEmpty()) {
                writeJson(e, 400, error("path required"));
                return;
            }
            Ds128Inode inode = fs.stat(path);
            if (inode == null) {
                writeJson(e, 404, error("file not found"));
                return;
            }
            if ((inode.i_mode & 0x8000) == 0) {
                writeJson(e, 400, error("not a file"));
                return;
            }
            byte[] data = fs.readFile(path);
            if (data == null) data = new byte[0];
            String name = path.substring(path.lastIndexOf('/') + 1);
            Headers h = e.getResponseHeaders();
            h.set("Content-Type", "application/octet-stream");
            h.set("Content-Disposition", "attachment; filename=\"" + name + "\"");
            e.sendResponseHeaders(200, data.length);
            try (OutputStream os = e.getResponseBody()) { os.write(data); }
        }
    }

    private final class UploadHandler implements HttpHandler {
        @Override public void handle(HttpExchange e) throws IOException {
            String path = queryParam(e, "path", "");
            if (path.isEmpty()) {
                writeJson(e, 400, error("path required"));
                return;
            }
            byte[] data;
            try (InputStream in = e.getRequestBody()) {
                data = in.readAllBytes();
            }
            fs.writeFile(path, data);
            writeJson(e, 200, okJson("uploaded"));
        }
    }

    private final class MkdirHandler implements HttpHandler {
        @Override public void handle(HttpExchange e) throws IOException {
            Map<String, String> body = parseJsonBody(e);
            String path = body.get("path");
            if (path == null || path.isBlank()) {
                writeJson(e, 400, error("path required"));
                return;
            }
            fs.mkdir(path);
            writeJson(e, 200, okJson("created"));
        }
    }

    private final class DeleteHandler implements HttpHandler {
        @Override public void handle(HttpExchange e) throws IOException {
            Map<String, String> body = parseJsonBody(e);
            String path = body.get("path");
            if (path == null || path.isBlank()) {
                writeJson(e, 400, error("path required"));
                return;
            }
            Ds128Inode inode = fs.stat(path);
            if (inode == null) {
                writeJson(e, 404, error("path not found"));
                return;
            }
            boolean ok;
            if ((inode.i_mode & 0x4000) != 0) {
                ok = fs.deleteDir(path);
            } else {
                ok = fs.deleteFile(path);
            }
            writeJson(e, 200, okJson(ok ? "deleted" : "failed"));
        }
    }

    private final class RenameHandler implements HttpHandler {
        @Override public void handle(HttpExchange e) throws IOException {
            Map<String, String> body = parseJsonBody(e);
            String path = body.get("path");
            String newPath = body.get("newPath");
            if (path == null || newPath == null) {
                writeJson(e, 400, error("path and newPath required"));
                return;
            }
            // 读取旧文件内容
            byte[] data = fs.readFile(path);
            Ds128Inode oldInode = fs.stat(path);
            if (oldInode == null) {
                writeJson(e, 404, error("path not found"));
                return;
            }
            // 创建新文件/目录
            if ((oldInode.i_mode & 0x4000) != 0) {
                fs.mkdir(newPath);
            } else {
                fs.writeFile(newPath, data != null ? data : new byte[0]);
            }
            // 删除旧文件/目录
            if ((oldInode.i_mode & 0x4000) != 0) {
                fs.deleteDir(path);
            } else {
                fs.deleteFile(path);
            }
            writeJson(e, 200, okJson("renamed"));
        }
    }

    private final class MoveHandler implements HttpHandler {
        @Override public void handle(HttpExchange e) throws IOException {
            Map<String, String> body = parseJsonBody(e);
            String path = body.get("path");
            String newPath = body.get("newPath");
            if (path == null || newPath == null) {
                writeJson(e, 400, error("path and newPath required"));
                return;
            }
            Ds128Inode oldInode = fs.stat(path);
            if (oldInode == null) {
                writeJson(e, 404, error("path not found"));
                return;
            }
            boolean isDir = (oldInode.i_mode & 0x4000) != 0;
            if (isDir) {
                // 递归复制目录（简化：仅复制空目录结构）
                fs.mkdir(newPath);
                // 递归复制子项
                copyDirRecursive(path, newPath);
                fs.deleteDir(path);
            } else {
                byte[] data = fs.readFile(path);
                fs.writeFile(newPath, data != null ? data : new byte[0]);
                fs.deleteFile(path);
            }
            writeJson(e, 200, okJson("moved"));
        }
    }

    private final class CopyHandler implements HttpHandler {
        @Override public void handle(HttpExchange e) throws IOException {
            Map<String, String> body = parseJsonBody(e);
            String path = body.get("path");
            String newPath = body.get("newPath");
            if (path == null || newPath == null) {
                writeJson(e, 400, error("path and newPath required"));
                return;
            }
            Ds128Inode oldInode = fs.stat(path);
            if (oldInode == null) {
                writeJson(e, 404, error("path not found"));
                return;
            }
            boolean isDir = (oldInode.i_mode & 0x4000) != 0;
            if (isDir) {
                fs.mkdir(newPath);
                copyDirRecursive(path, newPath);
            } else {
                byte[] data = fs.readFile(path);
                fs.writeFile(newPath, data != null ? data : new byte[0]);
            }
            writeJson(e, 200, okJson("copied"));
        }
    }

    private void copyDirRecursive(String srcDir, String dstDir) throws IOException {
        List<DsMftFileSystem.DirEntry> entries = fs.listDir(srcDir);
        for (DsMftFileSystem.DirEntry entry : entries) {
            String srcPath = joinPath(srcDir, entry.name);
            String dstPath = joinPath(dstDir, entry.name);
            if (entry.isDirectory) {
                fs.mkdir(dstPath);
                copyDirRecursive(srcPath, dstPath);
            } else {
                byte[] data = fs.readFile(srcPath);
                fs.writeFile(dstPath, data != null ? data : new byte[0]);
            }
        }
    }

    // ================== 工具方法 ==================

    private static String queryParam(HttpExchange e, String key, String def) {
        String q = e.getRequestURI().getRawQuery();
        if (q == null || q.isBlank()) return def;
        for (String part : q.split("&")) {
            int idx = part.indexOf('=');
            if (idx <= 0) continue;
            if (!part.substring(0, idx).equals(key)) continue;
            String v = part.substring(idx + 1);
            try { return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8); }
            catch (Exception ignored) { return v; }
        }
        return def;
    }

    private static Map<String, String> parseJsonBody(HttpExchange e) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        byte[] body;
        try (InputStream in = e.getRequestBody()) { body = in.readAllBytes(); }
        if (body.length == 0) return map;
        String s = new String(body, StandardCharsets.UTF_8).trim();
        if (!s.startsWith("{")) return map;
        s = s.substring(1, s.length() - 1).trim();
        if (s.isEmpty()) return map;
        // 简易 JSON 键值解析（仅支持字符串值）
        for (String pair : splitJsonPairs(s)) {
            pair = pair.trim();
            int colon = pair.indexOf(':');
            if (colon < 0) continue;
            String k = stripQuotes(pair.substring(0, colon).trim());
            String v = stripQuotes(pair.substring(colon + 1).trim());
            map.put(k, v);
        }
        return map;
    }

    private static List<String> splitJsonPairs(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inStr = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inStr = !inStr;
            } else if (!inStr && c == '{' ) {
                depth++;
            } else if (!inStr && c == '}' ) {
                depth--;
            } else if (!inStr && c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) parts.add(s.substring(start));
        return parts;
    }

    private static String stripQuotes(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static void writeJson(HttpExchange e, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        Headers h = e.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        e.sendResponseHeaders(status, body.length);
        try (OutputStream os = e.getResponseBody()) { os.write(body); }
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static String okJson(String message) {
        return "{\"ok\":true,\"message\":\"" + esc(message) + "\"}";
    }

    private static String error(String message) {
        return "{\"ok\":false,\"message\":\"" + esc(message) + "\"}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String joinPath(String parent, String name) {
        if (parent.endsWith("/")) return parent + name;
        return parent + "/" + name;
    }

    // ================== JSON 序列化 ==================

    private static String toJson(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof Number n) return n.toString();
        if (v instanceof String s) return "\"" + esc(s) + "\"";
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("\"").append(esc(String.valueOf(e.getKey()))).append("\":");
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
        return "\"" + esc(String.valueOf(v)) + "\"";
    }

    // ================== 前端 HTML ==================

    private static String indexHtml() {
        return """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>DsMftFileSystem 文件管理器</title>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: system-ui, -apple-system, "Segoe UI", Arial, sans-serif; background: #f5f5f5; height: 100vh; overflow: hidden; }
#app { display: flex; height: 100vh; flex-direction: column; }
/* 顶部工具栏 */
.toolbar { display: flex; align-items: center; gap: 8px; padding: 8px 12px; background: #fff; border-bottom: 1px solid #ddd; flex-shrink: 0; }
.toolbar button { padding: 6px 14px; border: 1px solid #ccc; background: #fff; border-radius: 4px; cursor: pointer; font-size: 13px; }
.toolbar button:hover { background: #f0f0f0; }
.toolbar button.primary { background: #1677ff; color: #fff; border-color: #1677ff; }
.toolbar button.primary:hover { background: #4096ff; }
.toolbar .spacer { flex: 1; }
.toolbar .breadcrumb { display: flex; align-items: center; gap: 4px; font-size: 13px; color: #333; }
.toolbar .breadcrumb span { cursor: pointer; color: #1677ff; }
.toolbar .breadcrumb span:hover { text-decoration: underline; }
/* 主体 */
.main { display: flex; flex: 1; overflow: hidden; }
/* 左侧树 */
.tree-panel { width: 240px; background: #fff; border-right: 1px solid #ddd; overflow-y: auto; padding: 8px 0; flex-shrink: 0; }
.tree-node { display: flex; align-items: center; padding: 4px 12px; cursor: pointer; user-select: none; font-size: 13px; }
.tree-node:hover { background: #f0f7ff; }
.tree-node.active { background: #e6f4ff; }
.tree-node .arrow { width: 16px; text-align: center; color: #999; font-size: 10px; transition: transform .2s; display: inline-block; }
.tree-node .arrow.expanded { transform: rotate(90deg); }
.tree-node .icon { margin-right: 6px; color: #faad14; }
.tree-node .icon.file-icon { color: #8c8c8c; }
.tree-children { padding-left: 16px; }
/* 右侧文件列表 */
.file-panel { flex: 1; background: #fff; overflow-y: auto; padding: 12px; }
.file-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.file-table th { text-align: left; padding: 8px; border-bottom: 2px solid #eee; color: #666; font-weight: 600; position: sticky; top: 0; background: #fff; }
.file-table td { padding: 8px; border-bottom: 1px solid #f0f0f0; vertical-align: middle; }
.file-table tr:hover { background: #fafafa; }
.file-table tr.selected { background: #e6f4ff; }
.file-table .name-cell { display: flex; align-items: center; gap: 6px; cursor: pointer; }
.file-table .name-cell .icon { font-size: 16px; }
.file-table .name-cell .icon.dir { color: #faad14; }
.file-table .name-cell .icon.file { color: #8c8c8c; }
.file-table .actions { display: flex; gap: 6px; }
.file-table .actions button { padding: 2px 8px; border: 1px solid #ddd; background: #fff; border-radius: 3px; cursor: pointer; font-size: 12px; }
.file-table .actions button:hover { background: #f0f0f0; }
/* 右键菜单 */
.context-menu { position: fixed; background: #fff; border: 1px solid #ddd; border-radius: 4px; box-shadow: 0 4px 12px rgba(0,0,0,.1); z-index: 1000; min-width: 140px; display: none; }
.context-menu-item { padding: 8px 14px; cursor: pointer; font-size: 13px; }
.context-menu-item:hover { background: #f0f7ff; }
.context-menu-divider { height: 1px; background: #eee; margin: 4px 0; }
/* 模态框 */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: none; align-items: center; justify-content: center; z-index: 2000; }
.modal-overlay.show { display: flex; }
.modal { background: #fff; border-radius: 6px; padding: 20px; width: 400px; box-shadow: 0 8px 24px rgba(0,0,0,.15); }
.modal h3 { margin-bottom: 14px; font-size: 15px; }
.modal input { width: 100%; padding: 8px; border: 1px solid #ddd; border-radius: 4px; margin-bottom: 14px; font-size: 13px; }
.modal .modal-actions { display: flex; justify-content: flex-end; gap: 8px; }
.modal .modal-actions button { padding: 6px 16px; border: 1px solid #ccc; background: #fff; border-radius: 4px; cursor: pointer; font-size: 13px; }
.modal .modal-actions button.primary { background: #1677ff; color: #fff; border-color: #1677ff; }
/* 上传输入 */
#fileInput { display: none; }
/* 空状态 */
.empty-state { text-align: center; padding: 60px 20px; color: #999; font-size: 14px; }
</style>
</head>
<body>
<div id="app">
  <div class="toolbar">
    <button class="primary" onclick="triggerUpload()">上传文件</button>
    <button onclick="showMkdirModal()">新建文件夹</button>
    <button onclick="refresh()">刷新</button>
    <div class="spacer"></div>
    <div class="breadcrumb" id="breadcrumb"></div>
    <div class="spacer"></div>
    <button onclick="pasteItem()" id="pasteBtn" style="display:none">粘贴</button>
  </div>
  <div class="main">
    <div class="tree-panel" id="treePanel"></div>
    <div class="file-panel" id="filePanel"></div>
  </div>
</div>

<div class="context-menu" id="contextMenu">
  <div class="context-menu-item" onclick="ctxDownload()">下载</div>
  <div class="context-menu-item" onclick="ctxRename()">重命名</div>
  <div class="context-menu-divider"></div>
  <div class="context-menu-item" onclick="ctxCopy()">复制</div>
  <div class="context-menu-item" onclick="ctxCut()">剪切</div>
  <div class="context-menu-divider"></div>
  <div class="context-menu-item" onclick="ctxDelete()" style="color:#ff4d4f">删除</div>
</div>

<div class="modal-overlay" id="modalOverlay">
  <div class="modal">
    <h3 id="modalTitle">输入</h3>
    <input type="text" id="modalInput" placeholder="">
    <div class="modal-actions">
      <button onclick="hideModal()">取消</button>
      <button class="primary" onclick="modalConfirm()">确定</button>
    </div>
  </div>
</div>

<input type="file" id="fileInput" onchange="handleFileSelect(event)">

<script>
let currentPath = '/';
let selectedItem = null;
let clipboard = { action: null, path: null, isDirectory: false };
let modalCallback = null;
let treeData = {}; // path -> loaded children

function esc(s) {
  return (s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function formatSize(n) {
  if (n === undefined || n === null) return '-';
  if (n < 1024) return n + ' B';
  if (n < 1024*1024) return (n/1024).toFixed(1) + ' KB';
  if (n < 1024*1024*1024) return (n/1024/1024).toFixed(1) + ' MB';
  return (n/1024/1024/1024).toFixed(1) + ' GB';
}

function formatTime(ts) {
  if (!ts) return '-';
  const d = new Date(ts);
  return d.getFullYear() + '-' + String(d.getMonth()+1).padStart(2,'0') + '-' + String(d.getDate()).padStart(2,'0')
    + ' ' + String(d.getHours()).padStart(2,'0') + ':' + String(d.getMinutes()).padStart(2,'0');
}

function getPathParts(p) {
  const parts = p.split('/').filter(Boolean);
  return parts;
}

function renderBreadcrumb() {
  const parts = getPathParts(currentPath);
  let html = '<span onclick="navigate(\'/\')">根目录</span>';
  let acc = '';
  for (const part of parts) {
    acc += '/' + part;
    html += ' / <span onclick="navigate(\'' + acc + '\')">' + esc(part) + '</span>';
  }
  document.getElementById('breadcrumb').innerHTML = html;
}

async function loadTree(path, parentEl) {
  try {
    const res = await fetch('/api/tree?path=' + encodeURIComponent(path));
    const data = await res.json();
    if (!data.ok) return;
    treeData[path] = data.nodes;
    renderTreeNode(parentEl, path, data.nodes);
  } catch (e) { console.error('tree load error', e); }
}

function renderTreeNode(parentEl, parentPath, nodes) {
  let container = parentEl.querySelector('.tree-children');
  if (!container) {
    container = document.createElement('div');
    container.className = 'tree-children';
    parentEl.appendChild(container);
  }
  container.innerHTML = '';
  for (const node of nodes) {
    const el = document.createElement('div');
    el.className = 'tree-node' + (node.path === currentPath ? ' active' : '');
    el.dataset.path = node.path;
    el.innerHTML = '<span class="arrow" onclick="toggleTree(event,\'' + node.path + '\',this)">'
      + (node.hasChildren ? '▶' : '') + '</span>'
      + '<span class="icon">📁</span>' + esc(node.name);
    el.onclick = () => navigate(node.path);
    container.appendChild(el);
  }
}

async function toggleTree(e, path, arrow) {
  e.stopPropagation();
  const nodeEl = arrow.closest('.tree-node');
  if (arrow.classList.contains('expanded')) {
    arrow.classList.remove('expanded');
    const children = nodeEl.querySelector('.tree-children');
    if (children) children.style.display = 'none';
  } else {
    arrow.classList.add('expanded');
    if (!treeData[path]) {
      await loadTree(path, nodeEl);
    } else {
      const children = nodeEl.querySelector('.tree-children');
      if (children) children.style.display = 'block';
    }
  }
}

async function initTree() {
  const panel = document.getElementById('treePanel');
  panel.innerHTML = '';
  const root = document.createElement('div');
  root.className = 'tree-node active';
  root.dataset.path = '/';
  root.innerHTML = '<span class="arrow expanded" onclick="toggleTree(event,\'/\',this)">▶</span>'
    + '<span class="icon">📁</span>根目录';
  root.onclick = () => navigate('/');
  panel.appendChild(root);
  await loadTree('/', root);
}

async function refresh() {
  await loadFileList(currentPath);
  // 刷新当前目录在树中的状态
  if (treeData[currentPath]) {
    const nodeEl = document.querySelector('.tree-node[data-path="' + currentPath + '"]');
    if (nodeEl) await loadTree(currentPath, nodeEl);
  }
}

async function loadFileList(path) {
  currentPath = path;
  renderBreadcrumb();
  try {
    const res = await fetch('/api/list?path=' + encodeURIComponent(path));
    const data = await res.json();
    if (!data.ok) {
      document.getElementById('filePanel').innerHTML = '<div class="empty-state">' + esc(data.message) + '</div>';
      return;
    }
    renderFileList(data.items);
    // 更新树高亮
    document.querySelectorAll('.tree-node').forEach(n => n.classList.remove('active'));
    const active = document.querySelector('.tree-node[data-path="' + path + '"]');
    if (active) active.classList.add('active');
  } catch (e) {
    document.getElementById('filePanel').innerHTML = '<div class="empty-state">加载失败</div>';
  }
}

function renderFileList(items) {
  const panel = document.getElementById('filePanel');
  if (items.length === 0) {
    panel.innerHTML = '<div class="empty-state">空文件夹</div>';
    return;
  }
  let html = '<table class="file-table"><thead><tr>'
    + '<th style="width:40%">名称</th><th>大小</th><th>修改时间</th><th>操作</th>'
    + '</tr></thead><tbody>';
  for (const it of items) {
    const icon = it.isDirectory ? '📁' : '📄';
    const cls = it.isDirectory ? 'dir' : 'file';
    html += '<tr data-name="' + esc(it.name) + '" data-dir="' + it.isDirectory + '"'
      + ' oncontextmenu="showContextMenu(event,\'' + esc(it.name) + '\',' + it.isDirectory + ')"'
      + ' onclick="selectItem(this,\'' + esc(it.name) + '\',' + it.isDirectory + ')">'
      + '<td><div class="name-cell" ondblclick="openItem(\'' + esc(it.name) + '\',' + it.isDirectory + ')">'
      + '<span class="icon ' + cls + '">' + icon + '</span>' + esc(it.name) + '</div></td>'
      + '<td>' + (it.isDirectory ? '-' : formatSize(it.size)) + '</td>'
      + '<td>' + formatTime(it.mtime) + '</td>'
      + '<td><div class="actions">'
      + (it.isDirectory ? '' : '<button onclick="downloadFile(\'' + esc(it.name) + '\')">下载</button>')
      + '<button onclick="renameItem(\'' + esc(it.name) + '\')">重命名</button>'
      + '<button onclick="deleteItem(\'' + esc(it.name) + '\',' + it.isDirectory + ')">删除</button>'
      + '</div></td></tr>';
  }
  html += '</tbody></table>';
  panel.innerHTML = html;
}

function navigate(path) {
  selectedItem = null;
  hideContextMenu();
  loadFileList(path);
}

function selectItem(row, name, isDir) {
  document.querySelectorAll('.file-table tr').forEach(r => r.classList.remove('selected'));
  row.classList.add('selected');
  selectedItem = { name, isDirectory: isDir };
}

function openItem(name, isDir) {
  const path = currentPath === '/' ? '/' + name : currentPath + '/' + name;
  if (isDir) {
    navigate(path);
  } else {
    downloadFile(name);
  }
}

// ================== 操作 ==================

function triggerUpload() {
  document.getElementById('fileInput').click();
}

async function handleFileSelect(e) {
  const file = e.target.files[0];
  if (!file) return;
  e.target.value = '';
  const path = currentPath === '/' ? '/' + file.name : currentPath + '/' + file.name;
  try {
    const res = await fetch('/api/upload?path=' + encodeURIComponent(path), {
      method: 'POST', body: file
    });
    const data = await res.json();
    if (data.ok) { refresh(); }
    else { alert('上传失败: ' + data.message); }
  } catch (err) { alert('上传失败: ' + err); }
}

function downloadFile(name) {
  const path = currentPath === '/' ? '/' + name : currentPath + '/' + name;
  window.open('/api/download?path=' + encodeURIComponent(path), '_blank');
}

async function deleteItem(name, isDir) {
  if (!confirm('确认删除 "' + name + '" ?')) return;
  const path = currentPath === '/' ? '/' + name : currentPath + '/' + name;
  try {
    const res = await fetch('/api/delete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path })
    });
    const data = await res.json();
    if (data.ok) { refresh(); }
    else { alert('删除失败: ' + data.message); }
  } catch (err) { alert('删除失败: ' + err); }
}

function showMkdirModal() {
  showModal('新建文件夹', '请输入文件夹名称', '', (name) => {
    if (!name) return;
    const path = currentPath === '/' ? '/' + name : currentPath + '/' + name;
    fetch('/api/mkdir', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path })
    }).then(r => r.json()).then(d => {
      if (d.ok) refresh();
      else alert('创建失败: ' + d.message);
    }).catch(e => alert('创建失败: ' + e));
  });
}

function renameItem(name) {
  showModal('重命名', '请输入新名称', name, (newName) => {
    if (!newName || newName === name) return;
    const path = currentPath === '/' ? '/' + name : currentPath + '/' + name;
    const newPath = currentPath === '/' ? '/' + newName : currentPath + '/' + newName;
    fetch('/api/rename', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path, newPath })
    }).then(r => r.json()).then(d => {
      if (d.ok) refresh();
      else alert('重命名失败: ' + d.message);
    }).catch(e => alert('重命名失败: ' + e));
  });
}

// ================== 右键菜单 ==================

function showContextMenu(e, name, isDir) {
  e.preventDefault();
  selectedItem = { name, isDirectory: isDir };
  document.querySelectorAll('.file-table tr').forEach(r => r.classList.remove('selected'));
  e.currentTarget.classList.add('selected');
  const menu = document.getElementById('contextMenu');
  menu.style.display = 'block';
  menu.style.left = e.pageX + 'px';
  menu.style.top = e.pageY + 'px';
}

function hideContextMenu() {
  document.getElementById('contextMenu').style.display = 'none';
}

document.addEventListener('click', () => hideContextMenu());

function ctxDownload() {
  if (!selectedItem) return;
  if (selectedItem.isDirectory) { alert('暂不支持下载文件夹'); return; }
  downloadFile(selectedItem.name);
}
function ctxRename() {
  if (!selectedItem) return;
  renameItem(selectedItem.name);
}
function ctxDelete() {
  if (!selectedItem) return;
  deleteItem(selectedItem.name, selectedItem.isDirectory);
}
function ctxCopy() {
  if (!selectedItem) return;
  const path = currentPath === '/' ? '/' + selectedItem.name : currentPath + '/' + selectedItem.name;
  clipboard = { action: 'copy', path, isDirectory: selectedItem.isDirectory };
  document.getElementById('pasteBtn').style.display = '';
  hideContextMenu();
}
function ctxCut() {
  if (!selectedItem) return;
  const path = currentPath === '/' ? '/' + selectedItem.name : currentPath + '/' + selectedItem.name;
  clipboard = { action: 'cut', path, isDirectory: selectedItem.isDirectory };
  document.getElementById('pasteBtn').style.display = '';
  hideContextMenu();
}

function pasteItem() {
  if (!clipboard.action || !clipboard.path) return;
  const name = clipboard.path.substring(clipboard.path.lastIndexOf('/') + 1);
  const newPath = currentPath === '/' ? '/' + name : currentPath + '/' + name;
  if (clipboard.path === newPath) { alert('不能粘贴到原位置'); return; }
  const api = clipboard.action === 'copy' ? '/api/copy' : '/api/move';
  fetch(api, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path: clipboard.path, newPath })
  }).then(r => r.json()).then(d => {
    if (d.ok) {
      clipboard = { action: null, path: null, isDirectory: false };
      document.getElementById('pasteBtn').style.display = 'none';
      refresh();
    } else {
      alert('粘贴失败: ' + d.message);
    }
  }).catch(e => alert('粘贴失败: ' + e));
}

// ================== 模态框 ==================

function showModal(title, hint, value, callback) {
  document.getElementById('modalTitle').textContent = title;
  const input = document.getElementById('modalInput');
  input.placeholder = hint;
  input.value = value || '';
  modalCallback = callback;
  document.getElementById('modalOverlay').classList.add('show');
  input.focus();
}

function hideModal() {
  document.getElementById('modalOverlay').classList.remove('show');
  modalCallback = null;
}

function modalConfirm() {
  if (modalCallback) {
    modalCallback(document.getElementById('modalInput').value.trim());
  }
  hideModal();
}

document.getElementById('modalInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') modalConfirm();
  if (e.key === 'Escape') hideModal();
});

// ================== 初始化 ==================

initTree();
loadFileList('/');
</script>
</body>
</html>
""";
    }
}
