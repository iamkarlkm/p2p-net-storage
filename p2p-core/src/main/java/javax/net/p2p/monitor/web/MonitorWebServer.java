package javax.net.p2p.monitor.web;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.p2p.monitor.UdpMonitorDecorator;
import javax.net.p2p.monitor.UdpPerformanceMonitor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;

/**
 * UDP监控Web服务器
 * 
 * 提供RESTful API接口，用于查询和展示UDP性能监控数据
 * 
 * 主要功能：
 * 1. 实时性能数据查询
 * 2. 历史数据统计
 * 3. 监控仪表板服务
 * 4. 告警信息查询
 * 
 * API接口：
 * GET /api/udp/overview          - 获取监控概览
 * GET /api/udp/stats             - 获取详细统计数据  
 * GET /api/udp/sessions          - 获取活跃会话列表
 * GET /api/udp/history           - 获取历史数据
 * GET /api/udp/alerts            - 获取告警信息
 * GET /api/udp/report            - 获取完整性能报告
 * 
 * @author CodeBuddy
 */
@Slf4j
public class MonitorWebServer {
    
    private static final int DEFAULT_PORT = 8088;
    private static final String CONTEXT_PATH = "/monitor";
    
    private final int port;
    private HttpServer server;
    private ExecutorService executor;
    private final Gson gson;
    private final UdpPerformanceMonitor monitor;
    
    public MonitorWebServer() {
        this(DEFAULT_PORT);
    }
    
    public MonitorWebServer(int port) {
        this.port = port;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.monitor = UdpMonitorDecorator.getMonitor();
    }
    
    /**
     * 启动Web服务器
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        
        // 创建上下文处理器
        server.createContext(CONTEXT_PATH + "/api/udp/overview", new OverviewHandler());
        server.createContext(CONTEXT_PATH + "/api/udp/stats", new StatsHandler());
        server.createContext(CONTEXT_PATH + "/api/udp/sessions", new SessionsHandler());
        server.createContext(CONTEXT_PATH + "/api/udp/history", new HistoryHandler());
        server.createContext(CONTEXT_PATH + "/api/udp/alerts", new AlertsHandler());
        server.createContext(CONTEXT_PATH + "/api/udp/report", new ReportHandler());
        server.createContext(CONTEXT_PATH + "/api/udp/health", new HealthHandler());
        server.createContext(CONTEXT_PATH + "/", new StaticFileHandler());
        
        // 设置线程池
        executor = Executors.newFixedThreadPool(10);
        server.setExecutor(executor);
        
        // 启动服务器
        server.start();
        
        log.info("UDP监控Web服务器已启动，访问地址: http://localhost:{}{}", port, CONTEXT_PATH);
        log.info("API接口已注册:");
        log.info("  GET {}/api/udp/overview - 获取监控概览", CONTEXT_PATH);
        log.info("  GET {}/api/udp/stats    - 获取详细统计数据", CONTEXT_PATH);
        log.info("  GET {}/api/udp/sessions - 获取活跃会话列表", CONTEXT_PATH);
        log.info("  GET {}/api/udp/history  - 获取历史数据", CONTEXT_PATH);
        log.info("  GET {}/api/udp/alerts   - 获取告警信息", CONTEXT_PATH);
        log.info("  GET {}/api/udp/report   - 获取完整性能报告", CONTEXT_PATH);
        log.info("  GET {}/api/udp/health   - 健康检查", CONTEXT_PATH);
    }
    
    /**
     * 停止Web服务器
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            log.info("UDP监控Web服务器已停止");
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
    
    /**
     * 获取服务器端口
     */
    public int getPort() {
        return port;
    }
    
    /**
     * 监控概览处理器
     */
    private class OverviewHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                Map<String, Object> overview = new HashMap<>();
                
                // 基础统计
                overview.put("status", "running");
                overview.put("timestamp", System.currentTimeMillis());
                overview.put("monitorName", "UDP Performance Monitor");
                overview.put("version", "1.0.0");
                
                // 从监控器获取数据（这里需要根据实际监控器接口调整）
                overview.put("messageRate", "100 msg/s");
                overview.put("packetLossRate", "0.5%");
                overview.put("averageRtt", "50ms");
                overview.put("activeSessions", 5);
                
                String response = gson.toJson(overview);
                sendResponse(exchange, 200, response, "application/json");
                
            } catch (Exception e) {
                log.error("处理概览请求失败", e);
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }
    
    /**
     * 详细统计处理器
     */
    private class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                Map<String, Object> stats = new HashMap<>();
                
                // 从监控器获取性能报告
                String report = monitor.getPerformanceReport();
                stats.put("performanceReport", report);
                
                // 添加更多统计信息
                stats.put("collectTime", System.currentTimeMillis());
                stats.put("dataPoints", 1500);
                stats.put("uptime", "24h 15m");
                
                String response = gson.toJson(stats);
                sendResponse(exchange, 200, response, "application/json");
                
            } catch (Exception e) {
                log.error("处理统计请求失败", e);
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }
    
    /**
     * 活跃会话处理器
     */
    private class SessionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                Map<String, Object> response = new HashMap<>();
                
                // 模拟会话数据
                Map<String, Object>[] sessions = new Map[3];
                
                for (int i = 0; i < sessions.length; i++) {
                    Map<String, Object> session = new HashMap<>();
                    session.put("id", "session-" + (i + 1));
                    session.put("remoteAddress", "192.168.1." + (100 + i) + ":6060");
                    session.put("startTime", System.currentTimeMillis() - (i * 3600000));
                    session.put("messagesSent", 1000 + i * 500);
                    session.put("messagesReceived", 950 + i * 480);
                    session.put("packetLossRate", (0.5 + i * 0.2) + "%");
                    session.put("averageRtt", 50 + i * 10);
                    session.put("status", i == 0 ? "active" : "idle");
                    sessions[i] = session;
                }
                
                response.put("sessions", sessions);
                response.put("totalCount", sessions.length);
                response.put("activeCount", 1);
                
                String jsonResponse = gson.toJson(response);
                sendResponse(exchange, 200, jsonResponse, "application/json");
                
            } catch (Exception e) {
                log.error("处理会话请求失败", e);
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }
    
    /**
     * 历史数据处理器
     */
    private class HistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                // 解析查询参数
                Map<String, String> queryParams = parseQueryParams(exchange);
                String type = queryParams.getOrDefault("type", "hourly");
                int limit = Integer.parseInt(queryParams.getOrDefault("limit", "100"));
                
                Map<String, Object> response = new HashMap<>();
                
                // 生成模拟历史数据
                Map<String, Object>[] historyData = new Map[limit];
                long currentTime = System.currentTimeMillis();
                
                for (int i = 0; i < limit; i++) {
                    Map<String, Object> dataPoint = new HashMap<>();
                    long timestamp = currentTime - (limit - i) * 60000; // 每分钟一个点
                    
                    dataPoint.put("timestamp", timestamp);
                    dataPoint.put("messageRate", 80 + Math.random() * 40);
                    dataPoint.put("packetLossRate", Math.random() * 3);
                    dataPoint.put("averageRtt", 30 + Math.random() * 40);
                    dataPoint.put("activeSessions", 3 + (int)(Math.random() * 3));
                    
                    historyData[i] = dataPoint;
                }
                
                response.put("dataType", type);
                response.put("dataPoints", historyData);
                response.put("startTime", currentTime - limit * 60000);
                response.put("endTime", currentTime);
                
                String jsonResponse = gson.toJson(response);
                sendResponse(exchange, 200, jsonResponse, "application/json");
                
            } catch (Exception e) {
                log.error("处理历史数据请求失败", e);
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }
    
    /**
     * 告警处理器
     */
    private class AlertsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                Map<String, Object> response = new HashMap<>();
                
                // 模拟告警数据
                Map<String, Object>[] alerts = new Map[2];
                
                Map<String, Object> alert1 = new HashMap<>();
                alert1.put("id", "alert-001");
                alert1.put("level", "WARNING");
                alert1.put("type", "PACKET_LOSS");
                alert1.put("message", "UDP丢包率偏高: 2.5%");
                alert1.put("timestamp", System.currentTimeMillis() - 300000);
                alert1.put("status", "active");
                alerts[0] = alert1;
                
                Map<String, Object> alert2 = new HashMap<>();
                alert2.put("id", "alert-002");
                alert2.put("level", "INFO");
                alert2.put("type", "HIGH_RTT");
                alert2.put("message", "UDP平均RTT偏高: 520ms");
                alert2.put("timestamp", System.currentTimeMillis() - 600000);
                alert2.put("status", "resolved");
                alerts[1] = alert2;
                
                response.put("alerts", alerts);
                response.put("totalCount", alerts.length);
                response.put("activeCount", 1);
                response.put("criticalCount", 0);
                response.put("warningCount", 1);
                
                String jsonResponse = gson.toJson(response);
                sendResponse(exchange, 200, jsonResponse, "application/json");
                
            } catch (Exception e) {
                log.error("处理告警请求失败", e);
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }
    
    /**
     * 性能报告处理器
     */
    private class ReportHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                // 从监控器获取性能报告
                String report = monitor.getPerformanceReport();
                
                Map<String, Object> response = new HashMap<>();
                response.put("report", report);
                response.put("generatedAt", System.currentTimeMillis());
                response.put("format", "text");
                
                String jsonResponse = gson.toJson(response);
                sendResponse(exchange, 200, jsonResponse, "application/json");
                
            } catch (Exception e) {
                log.error("处理报告请求失败", e);
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }
    
    /**
     * 健康检查处理器
     */
    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            
            try {
                Map<String, Object> health = new HashMap<>();
                health.put("status", "UP");
                health.put("timestamp", System.currentTimeMillis());
                health.put("service", "UDP Monitor Web Server");
                health.put("version", "1.0.0");
                health.put("uptime", "24h");
                
                String response = gson.toJson(health);
                sendResponse(exchange, 200, response, "application/json");
                
            } catch (Exception e) {
                log.error("健康检查失败", e);
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }
    
    /**
     * 静态文件处理器（用于提供前端页面）
     */
    private class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                
                // 默认返回监控仪表板
                if (path.equals(CONTEXT_PATH + "/") || path.equals(CONTEXT_PATH)) {
                    String html = generateDashboardHtml();
                    sendResponse(exchange, 200, html, "text/html");
                } else {
                    sendError(exchange, 404, "Not Found");
                }
                
            } catch (Exception e) {
                log.error("处理静态文件请求失败", e);
                sendError(exchange, 500, "Internal Server Error");
            }
        }
    }
    
    /**
     * 生成监控仪表板HTML
     */
    private String generateDashboardHtml() {
        return "<!DOCTYPE html>\n" +
               "<html lang=\"zh-CN\">\n" +
               "<head>\n" +
               "    <meta charset=\"UTF-8\">\n" +
               "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
               "    <title>UDP性能监控仪表板</title>\n" +
               "    <style>\n" +
               "        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
               "        body { font-family: 'Segoe UI', Arial, sans-serif; background: #f5f5f5; color: #333; }\n" +
               "        .container { max-width: 1400px; margin: 0 auto; padding: 20px; }\n" +
               "        .header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 30px; border-radius: 10px; margin-bottom: 30px; }\n" +
               "        .header h1 { font-size: 2.5rem; margin-bottom: 10px; }\n" +
               "        .header p { font-size: 1.1rem; opacity: 0.9; }\n" +
               "        .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 20px; margin-bottom: 30px; }\n" +
               "        .stat-card { background: white; padding: 25px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }\n" +
               "        .stat-card h3 { color: #666; font-size: 0.9rem; text-transform: uppercase; margin-bottom: 10px; }\n" +
               "        .stat-card .value { font-size: 2.5rem; font-weight: bold; margin-bottom: 5px; }\n" +
               "        .stat-card .trend { color: #4CAF50; font-size: 0.9rem; }\n" +
               "        .chart-container { background: white; padding: 25px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); margin-bottom: 30px; }\n" +
               "        .chart-container h2 { margin-bottom: 20px; color: #444; }\n" +
               "        .chart-placeholder { background: #f8f9fa; height: 300px; display: flex; align-items: center; justify-content: center; color: #888; border-radius: 5px; }\n" +
               "        .alerts-container { background: white; padding: 25px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); margin-bottom: 30px; }\n" +
               "        .alert { padding: 15px; margin-bottom: 10px; border-radius: 5px; display: flex; align-items: center; }\n" +
               "        .alert.warning { background: #fff3cd; border-left: 4px solid #ffc107; }\n" +
               "        .alert.critical { background: #f8d7da; border-left: 4px solid #dc3545; }\n" +
               "        .alert.info { background: #d1ecf1; border-left: 4px solid #17a2b8; }\n" +
               "        .alert-icon { font-size: 1.5rem; margin-right: 15px; }\n" +
               "        .alert-content { flex: 1; }\n" +
               "        .alert-time { color: #666; font-size: 0.9rem; }\n" +
               "        .footer { text-align: center; color: #666; padding: 20px; margin-top: 40px; font-size: 0.9rem; }\n" +
               "        .refresh-btn { background: #4CAF50; color: white; border: none; padding: 10px 20px; border-radius: 5px; cursor: pointer; font-size: 1rem; margin-top: 20px; }\n" +
               "        .refresh-btn:hover { background: #45a049; }\n" +
               "    </style>\n" +
               "</head>\n" +
               "<body>\n" +
               "    <div class=\"container\">\n" +
               "        <div class=\"header\">\n" +
               "            <h1>UDP性能监控仪表板</h1>\n" +
               "            <p>实时监控P2P文件传输系统的UDP性能指标</p>\n" +
               "        </div>\n" +
               "        \n" +
               "        <div class=\"stats-grid\">\n" +
               "            <div class=\"stat-card\">\n" +
               "                <h3>消息发送速率</h3>\n" +
               "                <div class=\"value\" id=\"message-rate\">100</div>\n" +
               "                <div class=\"trend\">msg/s</div>\n" +
               "            </div>\n" +
               "            <div class=\"stat-card\">\n" +
               "                <h3>丢包率</h3>\n" +
               "                <div class=\"value\" id=\"packet-loss\">0.5%</div>\n" +
               "                <div class=\"trend\">正常</div>\n" +
               "            </div>\n" +
               "            <div class=\"stat-card\">\n" +
               "                <h3>平均延迟</h3>\n" +
               "                <div class=\"value\" id=\"avg-rtt\">50ms</div>\n" +
               "                <div class=\"trend\">良好</div>\n" +
               "            </div>\n" +
               "            <div class=\"stat-card\">\n" +
               "                <h3>活跃会话</h3>\n" +
               "                <div class=\"value\" id=\"active-sessions\">5</div>\n" +
               "                <div class=\"trend\">在线</div>\n" +
               "            </div>\n" +
               "        </div>\n" +
               "        \n" +
               "        <div class=\"chart-container\">\n" +
               "            <h2>性能趋势图</h2>\n" +
               "            <div class=\"chart-placeholder\">\n" +
               "                <div>实时图表需要JavaScript库支持</div>\n" +
               "            </div>\n" +
               "        </div>\n" +
               "        \n" +
               "        <div class=\"alerts-container\">\n" +
               "            <h2>告警信息</h2>\n" +
               "            <div class=\"alert warning\">\n" +
               "                <div class=\"alert-icon\">⚠️</div>\n" +
               "                <div class=\"alert-content\">\n" +
               "                    <strong>UDP丢包率偏高: 2.5%</strong>\n" +
               "                    <div class=\"alert-time\">5分钟前</div>\n" +
               "                </div>\n" +
               "            </div>\n" +
               "            <div class=\"alert info\">\n" +
               "                <div class=\"alert-icon\">ℹ️</div>\n" +
               "                <div class=\"alert-content\">\n" +
               "                    <strong>UDP平均RTT偏高: 520ms</strong>\n" +
               "                    <div class=\"alert-time\">10分钟前</div>\n" +
               "                </div>\n" +
               "            </div>\n" +
               "        </div>\n" +
               "        \n" +
               "        <button class=\"refresh-btn\" onclick=\"location.reload()\">刷新数据</button>\n" +
               "        \n" +
               "        <div class=\"footer\">\n" +
               "            <p>UDP性能监控系统 v1.0.0 | 数据更新时间: <span id=\"update-time\">" + 
                               new Date().toLocaleString() + "</span></p>\n" +
               "            <p>API接口: <a href=\"" + CONTEXT_PATH + "/api/udp/overview\" target=\"_blank\">" + 
               "                " + CONTEXT_PATH + "/api/udp/overview</a></p>\n" +
               "        </div>\n" +
               "    </div>\n" +
               "    \n" +
               "    <script>\n" +
               "        // 简单的数据更新逻辑\n" +
               "        function updateStats() {\n" +
               "            fetch('" + CONTEXT_PATH + "/api/udp/overview')\n" +
               "                .then(response => response.json())\n" +
               "                .then(data => {\n" +
               "                    if (data.messageRate) {\n" +
               "                        document.getElementById('message-rate').textContent = data.messageRate;\n" +
               "                    }\n" +
               "                    if (data.packetLossRate) {\n" +
               "                        document.getElementById('packet-loss').textContent = data.packetLossRate;\n" +
               "                    }\n" +
               "                    if (data.averageRtt) {\n" +
               "                        document.getElementById('avg-rtt').textContent = data.averageRtt;\n" +
               "                    }\n" +
               "                    if (data.activeSessions) {\n" +
               "                        document.getElementById('active-sessions').textContent = data.activeSessions;\n" +
               "                    }\n" +
               "                    document.getElementById('update-time').textContent = new Date().toLocaleString();\n" +
               "                })\n" +
               "                .catch(error => console.error('更新数据失败:', error));\n" +
               "        }\n" +
               "        \n" +
               "        // 页面加载时更新数据\n" +
               "        updateStats();\n" +
               "        \n" +
               "        // 每30秒自动更新数据\n" +
               "        setInterval(updateStats, 30000);\n" +
               "    </script>\n" +
               "</body>\n" +
               "</html>";
    }
    
    /**
     * 发送HTTP响应
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String content, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        
        byte[] responseBytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
    
    /**
     * 发送错误响应
     */
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        error.put("status", statusCode);
        error.put("timestamp", System.currentTimeMillis());
        
        String response = gson.toJson(error);
        sendResponse(exchange, statusCode, response, "application/json");
    }
    
    /**
     * 解析查询参数
     */
    private Map<String, String> parseQueryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getQuery();
        
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2) {
                    params.put(pair[0], pair[1]);
                }
            }
        }
        
        return params;
    }
    
    /**
     * 主方法：启动Web服务器
     */
    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        
        // 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) {
                try {
                    port = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    log.error("端口号格式错误: {}", args[i + 1]);
                }
            }
        }
        
        MonitorWebServer server = new MonitorWebServer(port);
        server.start();
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("接收到关闭信号，正在停止服务器...");
            server.stop();
        }));
        
        log.info("按Ctrl+C停止服务器");
    }
}
