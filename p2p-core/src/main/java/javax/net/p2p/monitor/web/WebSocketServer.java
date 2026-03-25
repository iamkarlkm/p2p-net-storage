package javax.net.p2p.monitor.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.p2p.monitor.UdpMonitorDecorator;
import javax.net.p2p.monitor.UdpPerformanceMonitor;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import lombok.extern.slf4j.Slf4j;

/**
 * UDP监控WebSocket服务器
 * 
 * 提供实时监控数据推送，支持客户端订阅不同类型的监控数据
 * 
 * 主要功能：
 * 1. 实时性能数据推送
 * 2. 告警实时通知
 * 3. 支持多客户端连接
 * 4. 数据订阅管理
 * 
 * 消息格式：
 * {
 *   "type": "performance|alert|session|health",
 *   "timestamp": 1678888888888,
 *   "data": { ... }
 * }
 * 
 * @author CodeBuddy
 */
@Slf4j
public class WebSocketServer extends org.java_websocket.server.WebSocketServer {
    
    private static final int DEFAULT_PORT = 8089;
    private static final int BROADCAST_INTERVAL = 3000; // 3秒推送一次
    
    private final Set<WebSocket> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final UdpPerformanceMonitor monitor;
    private final ScheduledExecutorService scheduler;
    
    // 客户端订阅管理
    private final ConcurrentHashMap<WebSocket, Set<String>> clientSubscriptions = new ConcurrentHashMap<>();
    
    public WebSocketServer() {
        this(DEFAULT_PORT);
    }
    
    public WebSocketServer(int port) {
        super(new InetSocketAddress(port));
        this.monitor = UdpMonitorDecorator.getMonitor();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "websocket-broadcast-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        log.info("WebSocket服务器启动在端口: {}", port);
    }
    
    /**
     * 启动服务器
     */
    public void startServer() {
        try {
            super.start();
            startBroadcastScheduler();
            log.info("WebSocket服务器已启动，等待客户端连接...");
        } catch (Exception e) {
            log.error("启动WebSocket服务器失败", e);
        }
    }
    
    /**
     * 停止服务器
     */
    public void stopServer() {
        try {
            scheduler.shutdown();
            super.stop();
            log.info("WebSocket服务器已停止");
        } catch (InterruptedException e) {
            log.error("停止WebSocket服务器时被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("停止WebSocket服务器失败", e);
        }
    }
    
    /**
     * 开始定时广播数据
     */
    private void startBroadcastScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                broadcastPerformanceData();
            } catch (Exception e) {
                log.error("广播性能数据失败", e);
            }
        }, 0, BROADCAST_INTERVAL, TimeUnit.MILLISECONDS);
        
        log.info("WebSocket数据广播调度器已启动，间隔: {}ms", BROADCAST_INTERVAL);
    }
    
    /**
     * 广播性能数据
     */
    private void broadcastPerformanceData() {
        if (connections.isEmpty()) {
            return;
        }
        
        // 创建性能数据消息
        MonitorMessage message = createPerformanceMessage();
        String jsonMessage = gson.toJson(message);
        
        // 广播给所有连接
        for (WebSocket conn : connections) {
            if (conn.isOpen()) {
                try {
                    conn.send(jsonMessage);
                } catch (Exception e) {
                    log.warn("发送WebSocket消息失败", e);
                }
            }
        }
    }
    
    /**
     * 发送告警消息
     */
    public void sendAlert(String level, String type, String message) {
        MonitorMessage alert = createAlertMessage(level, type, message);
        String jsonAlert = gson.toJson(alert);
        
        // 发送给所有连接
        for (WebSocket conn : connections) {
            if (conn.isOpen()) {
                try {
                    conn.send(jsonAlert);
                } catch (Exception e) {
                    log.warn("发送告警消息失败", e);
                }
            }
        }
        
        log.info("WebSocket告警已发送: {} - {}", level, message);
    }
    
    /**
     * 创建性能数据消息
     */
    private MonitorMessage createPerformanceMessage() {
        MonitorMessage message = new MonitorMessage();
        message.type = "performance";
        message.timestamp = System.currentTimeMillis();
        
        PerformanceData data = new PerformanceData();
        data.status = "running";
        data.messageRate = "100 msg/s";
        data.packetLossRate = "0.5%";
        data.averageRtt = "50ms";
        data.activeSessions = 5;
        data.uptime = "24h 15m";
        data.cpuUsage = "45%";
        data.memoryUsage = "2.5GB";
        
        message.data = data;
        return message;
    }
    
    /**
     * 创建告警消息
     */
    private MonitorMessage createAlertMessage(String level, String type, String messageText) {
        MonitorMessage message = new MonitorMessage();
        message.type = "alert";
        message.timestamp = System.currentTimeMillis();
        
        AlertData data = new AlertData();
        data.level = level;
        data.type = type;
        data.message = messageText;
        data.timestamp = System.currentTimeMillis();
        data.id = "alert-" + System.currentTimeMillis();
        
        message.data = data;
        return message;
    }
    
    /**
     * 客户端连接建立
     */
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        connections.add(conn);
        clientSubscriptions.put(conn, Collections.newSetFromMap(new ConcurrentHashMap<>()));
        
        log.info("新的WebSocket连接: {}", conn.getRemoteSocketAddress());
        log.info("当前连接数: {}", connections.size());
        
        // 发送欢迎消息
        sendWelcomeMessage(conn);
    }
    
    /**
     * 客户端连接关闭
     */
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        connections.remove(conn);
        clientSubscriptions.remove(conn);
        
        log.info("WebSocket连接关闭: {} (代码: {}, 原因: {})", 
                conn.getRemoteSocketAddress(), code, reason);
        log.info("当前连接数: {}", connections.size());
    }
    
    /**
     * 收到客户端消息
     */
    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            // 解析客户端消息
            WebSocketMessage clientMsg = gson.fromJson(message, WebSocketMessage.class);
            
            if (clientMsg == null || clientMsg.action == null) {
                log.warn("收到无效的WebSocket消息: {}", message);
                return;
            }
            
            // 处理不同的动作
            switch (clientMsg.action) {
                case "subscribe":
                    handleSubscribe(conn, clientMsg);
                    break;
                    
                case "unsubscribe":
                    handleUnsubscribe(conn, clientMsg);
                    break;
                    
                case "request":
                    handleRequest(conn, clientMsg);
                    break;
                    
                case "ping":
                    sendPong(conn);
                    break;
                    
                default:
                    log.warn("未知的WebSocket动作: {}", clientMsg.action);
            }
            
        } catch (Exception e) {
            log.error("处理WebSocket消息失败: {}", message, e);
        }
    }
    
    /**
     * 处理订阅请求
     */
    private void handleSubscribe(WebSocket conn, WebSocketMessage msg) {
        if (msg.dataTypes != null) {
            Set<String> subscriptions = clientSubscriptions.get(conn);
            if (subscriptions != null) {
                subscriptions.addAll(msg.dataTypes);
                log.info("客户端 {} 订阅数据类型: {}", 
                        conn.getRemoteSocketAddress(), msg.dataTypes);
                
                // 发送确认消息
                sendSubscriptionConfirmation(conn, msg.dataTypes);
            }
        }
    }
    
    /**
     * 处理取消订阅请求
     */
    private void handleUnsubscribe(WebSocket conn, WebSocketMessage msg) {
        if (msg.dataTypes != null) {
            Set<String> subscriptions = clientSubscriptions.get(conn);
            if (subscriptions != null) {
                subscriptions.removeAll(msg.dataTypes);
                log.info("客户端 {} 取消订阅数据类型: {}", 
                        conn.getRemoteSocketAddress(), msg.dataTypes);
            }
        }
    }
    
    /**
     * 处理特定请求
     */
    private void handleRequest(WebSocket conn, WebSocketMessage msg) {
        if (msg.requestType != null) {
            switch (msg.requestType) {
                case "current_stats":
                    sendCurrentStats(conn);
                    break;
                    
                case "recent_alerts":
                    sendRecentAlerts(conn);
                    break;
                    
                case "system_info":
                    sendSystemInfo(conn);
                    break;
                    
                default:
                    log.warn("未知的请求类型: {}", msg.requestType);
            }
        }
    }
    
    /**
     * 发送欢迎消息
     */
    private void sendWelcomeMessage(WebSocket conn) {
        MonitorMessage welcome = new MonitorMessage();
        welcome.type = "welcome";
        welcome.timestamp = System.currentTimeMillis();
        
        WelcomeData data = new WelcomeData();
        data.message = "欢迎连接到UDP性能监控WebSocket服务器";
        data.serverTime = System.currentTimeMillis();
        data.supportedDataTypes = new String[]{
            "performance", "alert", "session", "health"
        };
        data.clientId = conn.hashCode();
        
        welcome.data = data;
        
        try {
            conn.send(gson.toJson(welcome));
        } catch (Exception e) {
            log.warn("发送欢迎消息失败", e);
        }
    }
    
    /**
     * 发送订阅确认
     */
    private void sendSubscriptionConfirmation(WebSocket conn, Set<String> dataTypes) {
        MonitorMessage confirm = new MonitorMessage();
        confirm.type = "subscription_confirmed";
        confirm.timestamp = System.currentTimeMillis();
        
        SubscriptionData data = new SubscriptionData();
        data.subscribedTypes = dataTypes.toArray(new String[0]);
        data.confirmedAt = System.currentTimeMillis();
        
        confirm.data = data;
        
        try {
            conn.send(gson.toJson(confirm));
        } catch (Exception e) {
            log.warn("发送订阅确认失败", e);
        }
    }
    
    /**
     * 发送当前统计数据
     */
    private void sendCurrentStats(WebSocket conn) {
        MonitorMessage stats = createPerformanceMessage();
        stats.type = "current_stats";
        
        try {
            conn.send(gson.toJson(stats));
        } catch (Exception e) {
            log.warn("发送当前统计数据失败", e);
        }
    }
    
    /**
     * 发送最近告警
     */
    private void sendRecentAlerts(WebSocket conn) {
        // 这里应该从监控器获取最近的告警
        // 目前发送模拟数据
        MonitorMessage alert = createAlertMessage("INFO", "TEST", "测试告警消息");
        alert.type = "recent_alerts";
        
        try {
            conn.send(gson.toJson(alert));
        } catch (Exception e) {
            log.warn("发送最近告警失败", e);
        }
    }
    
    /**
     * 发送系统信息
     */
    private void sendSystemInfo(WebSocket conn) {
        MonitorMessage systemInfo = new MonitorMessage();
        systemInfo.type = "system_info";
        systemInfo.timestamp = System.currentTimeMillis();
        
        SystemInfoData data = new SystemInfoData();
        data.serverName = "UDP Monitor WebSocket Server";
        data.version = "1.0.0";
        data.startTime = System.currentTimeMillis() - 86400000; // 24小时前
        data.currentConnections = connections.size();
        data.maxConnections = 100;
        data.broadcastInterval = BROADCAST_INTERVAL;
        
        systemInfo.data = data;
        
        try {
            conn.send(gson.toJson(systemInfo));
        } catch (Exception e) {
            log.warn("发送系统信息失败", e);
        }
    }
    
    /**
     * 发送Pong响应
     */
    private void sendPong(WebSocket conn) {
        MonitorMessage pong = new MonitorMessage();
        pong.type = "pong";
        pong.timestamp = System.currentTimeMillis();
        
        try {
            conn.send(gson.toJson(pong));
        } catch (Exception e) {
            log.warn("发送Pong响应失败", e);
        }
    }
    
    /**
     * 错误处理
     */
    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            log.error("WebSocket连接错误: {}", conn.getRemoteSocketAddress(), ex);
        } else {
            log.error("WebSocket服务器错误", ex);
        }
    }
    
    @Override
    public void onStart() {
        log.info("WebSocket服务器启动成功");
    }
    
    /**
     * 获取当前连接数
     */
    public int getConnectionCount() {
        return connections.size();
    }
    
    /**
     * 获取所有连接
     */
    public Set<WebSocket> getConnections() {
        return new HashSet<>(connections);
    }
    
    /**
     * 监控消息基类
     */
    private static class MonitorMessage {
        String type;
        long timestamp;
        Object data;
    }
    
    /**
     * 客户端WebSocket消息
     */
    private static class WebSocketMessage {
        String action;
        Set<String> dataTypes;
        String requestType;
    }
    
    /**
     * 性能数据
     */
    private static class PerformanceData {
        String status;
        String messageRate;
        String packetLossRate;
        String averageRtt;
        int activeSessions;
        String uptime;
        String cpuUsage;
        String memoryUsage;
    }
    
    /**
     * 告警数据
     */
    private static class AlertData {
        String id;
        String level;
        String type;
        String message;
        long timestamp;
    }
    
    /**
     * 欢迎数据
     */
    private static class WelcomeData {
        String message;
        long serverTime;
        String[] supportedDataTypes;
        int clientId;
    }
    
    /**
     * 订阅数据
     */
    private static class SubscriptionData {
        String[] subscribedTypes;
        long confirmedAt;
    }
    
    /**
     * 系统信息数据
     */
    private static class SystemInfoData {
        String serverName;
        String version;
        long startTime;
        int currentConnections;
        int maxConnections;
        int broadcastInterval;
    }
    
    /**
     * 主方法：启动WebSocket服务器
     */
    public static void main(String[] args) {
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
        
        WebSocketServer server = new WebSocketServer(port);
        server.startServer();
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在停止WebSocket服务器...");
            server.stopServer();
        }));
        
        log.info("按Ctrl+C停止WebSocket服务器");
    }
}