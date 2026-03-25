package javax.net.p2p.monitor.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 静态文件处理器
 * 
 * 处理前端静态资源文件（HTML、CSS、JS等）的请求
 * 
 * 支持的文件类型：
 * .html, .css, .js, .json, .png, .jpg, .jpeg, .gif, .ico, .svg, .ttf, .woff, .woff2
 * 
 * @author CodeBuddy
 */
public class StaticFileHandler implements HttpHandler {
    
    private static final Map<String, String> CONTENT_TYPES = new HashMap<>();
    
    static {
        // 初始化内容类型映射
        CONTENT_TYPES.put("html", "text/html; charset=UTF-8");
        CONTENT_TYPES.put("htm", "text/html; charset=UTF-8");
        CONTENT_TYPES.put("css", "text/css; charset=UTF-8");
        CONTENT_TYPES.put("js", "application/javascript; charset=UTF-8");
        CONTENT_TYPES.put("json", "application/json; charset=UTF-8");
        CONTENT_TYPES.put("png", "image/png");
        CONTENT_TYPES.put("jpg", "image/jpeg");
        CONTENT_TYPES.put("jpeg", "image/jpeg");
        CONTENT_TYPES.put("gif", "image/gif");
        CONTENT_TYPES.put("ico", "image/x-icon");
        CONTENT_TYPES.put("svg", "image/svg+xml");
        CONTENT_TYPES.put("ttf", "font/ttf");
        CONTENT_TYPES.put("woff", "font/woff");
        CONTENT_TYPES.put("woff2", "font/woff2");
        CONTENT_TYPES.put("txt", "text/plain; charset=UTF-8");
        CONTENT_TYPES.put("xml", "application/xml; charset=UTF-8");
        CONTENT_TYPES.put("pdf", "application/pdf");
    }
    
    private final String basePath;
    private final String defaultFile;
    
    /**
     * 构造函数
     * @param basePath 基础路径（相对于classpath）
     * @param defaultFile 默认文件
     */
    public StaticFileHandler(String basePath, String defaultFile) {
        this.basePath = basePath.startsWith("/") ? basePath : "/" + basePath;
        this.defaultFile = defaultFile;
    }
    
    /**
     * 构造函数（默认基础路径为static，默认文件为index.html）
     */
    public StaticFileHandler() {
        this("static", "index.html");
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        
        if (!"GET".equals(method)) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }
        
        try {
            // 获取请求路径
            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            
            // 如果请求根路径，返回默认文件
            if (path.equals("/") || path.equals("") || path.endsWith("/")) {
                path = "/" + defaultFile;
            }
            
            // 构建资源路径
            String resourcePath = basePath + path;
            if (resourcePath.endsWith("/")) {
                resourcePath += defaultFile;
            }
            
            // 获取资源
            URL resourceUrl = getClass().getClassLoader().getResource(resourcePath);
            
            if (resourceUrl == null) {
                // 如果找不到文件，尝试作为API请求处理
                if (path.startsWith("/api/")) {
                    // 这里可以转发到API处理器
                    sendApiNotAvailable(exchange);
                } else {
                    // 返回404错误
                    sendError(exchange, 404, "File Not Found: " + path);
                }
                return;
            }
            
            // 确定内容类型
            String contentType = determineContentType(resourcePath);
            
            // 读取文件内容
            try (InputStream is = resourceUrl.openStream()) {
                byte[] data = is.readAllBytes();
                
                // 设置响应头
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
                exchange.getResponseHeaders().set("Pragma", "no-cache");
                exchange.getResponseHeaders().set("Expires", "0");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                
                // 发送响应
                exchange.sendResponseHeaders(200, data.length);
                
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(data);
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }
    
    /**
     * 根据文件扩展名确定内容类型
     */
    private String determineContentType(String filePath) {
        int dotIndex = filePath.lastIndexOf('.');
        if (dotIndex > 0) {
            String extension = filePath.substring(dotIndex + 1).toLowerCase();
            String contentType = CONTENT_TYPES.get(extension);
            if (contentType != null) {
                return contentType;
            }
        }
        return "application/octet-stream";
    }
    
    /**
     * 发送错误响应
     */
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        String html = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <title>Error " + statusCode + "</title>\n" +
                "    <style>\n" +
                "        body { font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }\n" +
                "        .error-container { background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }\n" +
                "        h1 { color: #d32f2f; margin-bottom: 20px; }\n" +
                "        .error-code { font-size: 4rem; font-weight: bold; color: #d32f2f; margin-bottom: 10px; }\n" +
                "        .error-message { font-size: 1.2rem; color: #666; margin-bottom: 20px; }\n" +
                "        .back-link { color: #1976d2; text-decoration: none; }\n" +
                "        .back-link:hover { text-decoration: underline; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"error-container\">\n" +
                "        <div class=\"error-code\">" + statusCode + "</div>\n" +
                "        <h1>" + getStatusText(statusCode) + "</h1>\n" +
                "        <div class=\"error-message\">" + message + "</div>\n" +
                "        <a href=\"/\" class=\"back-link\">返回监控仪表板</a>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
        
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        byte[] response = html.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, response.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }
    
    /**
     * 发送API不可用响应
     */
    private void sendApiNotAvailable(HttpExchange exchange) throws IOException {
        String json = "{\"error\": \"API endpoint not available in static file mode\", \"status\": 503}";
        
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(503, response.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }
    
    /**
     * 获取状态码对应的文本描述
     */
    private String getStatusText(int statusCode) {
        switch (statusCode) {
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }
}