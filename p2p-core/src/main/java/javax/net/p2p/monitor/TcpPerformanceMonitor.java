package javax.net.p2p.monitor;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import lombok.extern.slf4j.Slf4j;

/**
 * TCP性能监控器
 * 
 * 功能特性：
 * 1. 实时监控：实时监控TCP连接性能指标
 * 2. 多维统计：连接数、吞吐量、延迟、错误率等多维度统计
 * 3. 性能分析：性能瓶颈分析和优化建议
 * 4. 告警系统：异常情况自动告警
 * 5. 报表生成：定期生成性能报表
 * 6. 趋势分析：性能趋势分析和预测
 * 
 * 监控指标：
 * - 连接统计：活跃连接数、连接建立/关闭速率
 * - 吞吐量统计：消息发送/接收速率、字节吞吐量
 * - 延迟统计：消息处理延迟、网络往返延迟
 * - 错误统计：连接错误、消息错误、超时错误
 * - 资源统计：内存使用、线程使用、缓冲区使用
 * 
 * @version 1.0
 * @since 2026-03-13
 */
@Slf4j
public class TcpPerformanceMonitor {
    
    // 单例实例
    private static volatile TcpPerformanceMonitor instance;
    
    // 监控配置
    private final MonitorConfig config;
    private volatile boolean monitoring = false;
    
    // 监控数据存储
    private final ConcurrentMap<String, ConnectionMetrics> connectionMetrics = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ServerMetrics> serverMetrics = new ConcurrentHashMap<>();
    private final GlobalMetrics globalMetrics = new GlobalMetrics();
    
    // 监控线程
    private ScheduledExecutorService scheduler;
    
    /**
     * 私有构造函数
     */
    private TcpPerformanceMonitor() {
        this.config = new MonitorConfig();
    }
    
    /**
     * 获取单例实例
     */
    public static TcpPerformanceMonitor getInstance() {
        if (instance == null) {
            synchronized (TcpPerformanceMonitor.class) {
                if (instance == null) {
                    instance = new TcpPerformanceMonitor();
                }
            }
        }
        return instance;
    }
    
    /**
     * 启动监控
     */
    public void start() {
        if (monitoring) {
            log.warn("Performance monitor is already running");
            return;
        }
        
        monitoring = true;
        scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "TcpPerformanceMonitor-Scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // 启动定期监控任务
        scheduler.scheduleAtFixedRate(this::collectMetrics, 
            config.collectionIntervalMs, config.collectionIntervalMs, TimeUnit.MILLISECONDS);
        
        // 启动定期报告任务
        scheduler.scheduleAtFixedRate(this::generateReport,
            config.reportIntervalMs, config.reportIntervalMs, TimeUnit.MILLISECONDS);
        
        log.info("TCP performance monitor started");
        log.info("Monitor configuration: {}", config);
    }
    
    /**
     * 停止监控
     */
    public void stop() {
        if (!monitoring) {
            return;
        }
        
        monitoring = false;
        
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        
        log.info("TCP performance monitor stopped");
    }
    
    /**
     * 注册连接
     */
    public void registerConnection(Channel channel) {
        if (!monitoring) {
            return;
        }
        
        String connectionId = getConnectionId(channel);
        ConnectionMetrics metrics = new ConnectionMetrics();
        connectionMetrics.put(connectionId, metrics);
        
        globalMetrics.activeConnections.incrementAndGet();
        globalMetrics.totalConnections.increment();
        
        log.debug("Connection registered: {}", connectionId);
    }
    
    /**
     * 注销连接
     */
    public void unregisterConnection(Channel channel) {
        if (!monitoring) {
            return;
        }
        
        String connectionId = getConnectionId(channel);
        connectionMetrics.remove(connectionId);
        
        globalMetrics.activeConnections.decrementAndGet();
        
        log.debug("Connection unregistered: {}", connectionId);
    }
    
    /**
     * 记录消息接收
     */
    public void recordMessageReceived(Channel channel, int messageSize) {
        if (!monitoring) {
            return;
        }
        
        String connectionId = getConnectionId(channel);
        ConnectionMetrics metrics = connectionMetrics.get(connectionId);
        
        if (metrics != null) {
            metrics.messagesReceived.increment();
            metrics.bytesReceived.add(messageSize);
            metrics.lastActivityTime = System.currentTimeMillis();
        }
        
        globalMetrics.totalMessagesReceived.increment();
        globalMetrics.totalBytesReceived.add(messageSize);
    }
    
    /**
     * 记录消息发送
     */
    public void recordMessageSent(Channel channel, int messageSize) {
        if (!monitoring) {
            return;
        }
        
        String connectionId = getConnectionId(channel);
        ConnectionMetrics metrics = connectionMetrics.get(connectionId);
        
        if (metrics != null) {
            metrics.messagesSent.increment();
            metrics.bytesSent.add(messageSize);
            metrics.lastActivityTime = System.currentTimeMillis();
        }
        
        globalMetrics.totalMessagesSent.increment();
        globalMetrics.totalBytesSent.add(messageSize);
    }
    
    /**
     * 记录处理延迟
     */
    public void recordProcessingDelay(Channel channel, long delayMs) {
        if (!monitoring) {
            return;
        }
        
        String connectionId = getConnectionId(channel);
        ConnectionMetrics metrics = connectionMetrics.get(connectionId);
        
        if (metrics != null) {
            metrics.processingDelays.add(delayMs);
            metrics.totalProcessingTime.add(delayMs);
            
            // 更新最大延迟
            if (delayMs > metrics.maxProcessingDelay.get()) {
                metrics.maxProcessingDelay.set(delayMs);
            }
        }
        
        globalMetrics.totalProcessingTime.add(delayMs);
    }
    
    /**
     * 记录错误
     */
    public void recordError(Channel channel, String errorType, String errorMessage) {
        if (!monitoring) {
            return;
        }
        
        String connectionId = getConnectionId(channel);
        ConnectionMetrics metrics = connectionMetrics.get(connectionId);
        
        if (metrics != null) {
            metrics.errors.increment();
            metrics.lastErrorTime = System.currentTimeMillis();
            metrics.lastErrorMessage = errorMessage;
        }
        
        globalMetrics.totalErrors.increment();
        
        // 触发告警
        if (shouldAlert(errorType)) {
            triggerAlert(errorType, errorMessage, connectionId);
        }
    }
    
    /**
     * 收集指标数据
     */
    private void collectMetrics() {
        if (!monitoring) {
            return;
        }
        
        try {
            long now = System.currentTimeMillis();
            
            // 收集连接指标
            for (ConnectionMetrics metrics : connectionMetrics.values()) {
                metrics.collect(now);
            }
            
            // 收集全局指标
            globalMetrics.collect(now);
            
            // 检查连接健康状况
            checkConnectionHealth(now);
            
            // 检查系统资源
            checkSystemResources();
            
        } catch (Exception e) {
            log.error("Error collecting performance metrics", e);
        }
    }
    
    /**
     * 检查连接健康状态
     */
    private void checkConnectionHealth(long now) {
        for (var entry : connectionMetrics.entrySet()) {
            String connectionId = entry.getKey();
            ConnectionMetrics metrics = entry.getValue();
            
            long idleTime = now - metrics.lastActivityTime;
            if (idleTime > config.maxIdleTimeMs) {
                log.warn("Connection idle for too long: {}, idleTime={}ms", 
                        connectionId, idleTime);
            }
            
            if (metrics.errors.intValue() > config.maxErrorsPerConnection) {
                log.warn("Connection has too many errors: {}, errors={}", 
                        connectionId, metrics.errors.intValue());
            }
        }
    }
    
    /**
     * 检查系统资源
     */
    private void checkSystemResources() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        double memoryUsage = (double) usedMemory / maxMemory * 100;
        
        if (memoryUsage > config.memoryUsageThreshold) {
            log.warn("High memory usage: {:.2f}%", memoryUsage);
        }
        
        int threadCount = Thread.activeCount();
        if (threadCount > config.maxThreadCount) {
            log.warn("High thread count: {}", threadCount);
        }
    }
    
    /**
     * 生成性能报告
     */
    private void generateReport() {
        if (!monitoring) {
            return;
        }
        
        try {
            PerformanceReport report = new PerformanceReport(
                System.currentTimeMillis(),
                globalMetrics,
                connectionMetrics.size(),
                getServerMetricsSummary()
            );
            
            log.info("Performance report generated: {}", report);
            
            // 可以在这里将报告保存到文件或发送到监控系统
            saveReport(report);
            
        } catch (Exception e) {
            log.error("Error generating performance report", e);
        }
    }
    
    /**
     * 保存报告
     */
    private void saveReport(PerformanceReport report) {
        // 这里可以实现将报告保存到文件、数据库或发送到监控系统
        // 目前只记录到日志
        if (log.isDebugEnabled()) {
            log.debug("Performance report details: {}", report.getDetailedMetrics());
        }
    }
    
    /**
     * 判断是否需要告警
     */
    private boolean shouldAlert(String errorType) {
        // 根据错误类型和阈值判断是否需要告警
        return errorType.contains("fatal") || 
               errorType.contains("timeout") || 
               errorType.contains("connection");
    }
    
    /**
     * 触发告警
     */
    private void triggerAlert(String errorType, String errorMessage, String connectionId) {
        Alert alert = new Alert(
            System.currentTimeMillis(),
            errorType,
            errorMessage,
            connectionId,
            AlertSeverity.WARNING
        );
        
        log.warn("Performance alert triggered: {}", alert);
        
        // 可以在这里将告警发送到告警系统
        // 例如：发送邮件、短信、Slack消息等
    }
    
    /**
     * 获取服务器指标摘要
     */
    private String getServerMetricsSummary() {
        StringBuilder summary = new StringBuilder();
        for (var entry : serverMetrics.entrySet()) {
            summary.append(entry.getKey()).append(": ").append(entry.getValue()).append("; ");
        }
        return summary.toString();
    }
    
    /**
     * 获取连接ID
     */
    private String getConnectionId(Channel channel) {
        if (channel == null) {
            return "null";
        }
        
        try {
            InetSocketAddress remoteAddress = (InetSocketAddress) channel.remoteAddress();
            InetSocketAddress localAddress = (InetSocketAddress) channel.localAddress();
            
            return String.format("%s:%d->%s:%d", 
                localAddress.getAddress().getHostAddress(), localAddress.getPort(),
                remoteAddress.getAddress().getHostAddress(), remoteAddress.getPort());
        } catch (Exception e) {
            return channel.id().asShortText();
        }
    }
    
    /**
     * 获取监控状态
     */
    public boolean isMonitoring() {
        return monitoring;
    }
    
    /**
     * 获取全局指标
     */
    public GlobalMetrics getGlobalMetrics() {
        return globalMetrics;
    }
    
    /**
     * 获取连接指标
     */
    public ConnectionMetrics getConnectionMetrics(String connectionId) {
        return connectionMetrics.get(connectionId);
    }
    
    /**
     * 获取所有连接指标
     */
    public ConcurrentMap<String, ConnectionMetrics> getAllConnectionMetrics() {
        return new ConcurrentHashMap<>(connectionMetrics);
    }
    
    /**
     * 连接指标类
     */
    public static class ConnectionMetrics {
        // 消息统计
        public final LongAdder messagesReceived = new LongAdder();
        public final LongAdder messagesSent = new LongAdder();
        public final LongAdder bytesReceived = new LongAdder();
        public final LongAdder bytesSent = new LongAdder();
        
        // 延迟统计
        public final LongAdder processingDelays = new LongAdder();
        public final LongAdder totalProcessingTime = new LongAdder();
        public final AtomicLong maxProcessingDelay = new AtomicLong(0);
        
        // 错误统计
        public final LongAdder errors = new LongAdder();
        public volatile long lastErrorTime = 0;
        public volatile String lastErrorMessage = "";
        
        // 时间统计
        public volatile long lastActivityTime = System.currentTimeMillis();
        public volatile long createdTime = System.currentTimeMillis();
        
        // 历史统计
        private final ConcurrentHashMap<Long, MetricsSnapshot> history = new ConcurrentHashMap<>();
        
        public void collect(long timestamp) {
            MetricsSnapshot snapshot = new MetricsSnapshot(
                timestamp,
                messagesReceived.sum(),
                messagesSent.sum(),
                bytesReceived.sum(),
                bytesSent.sum(),
                errors.sum(),
                processingDelays.sum(),
                maxProcessingDelay.get()
            );
            
            history.put(timestamp, snapshot);
            
            // 清理旧的历史数据（保留最近1小时）
            long cutoff = timestamp - 3600000; // 1小时
            history.keySet().removeIf(key -> key < cutoff);
        }
        
        public double getMessageRate(long periodMs) {
            long now = System.currentTimeMillis();
            long cutoff = now - periodMs;
            
            long messageCount = history.entrySet().stream()
                .filter(entry -> entry.getKey() >= cutoff)
                .mapToLong(entry -> entry.getValue().messagesReceived + entry.getValue().messagesSent)
                .sum();
            
            return periodMs > 0 ? (double) messageCount / (periodMs / 1000.0) : 0;
        }
        
        public double getByteRate(long periodMs) {
            long now = System.currentTimeMillis();
            long cutoff = now - periodMs;
            
            long byteCount = history.entrySet().stream()
                .filter(entry -> entry.getKey() >= cutoff)
                .mapToLong(entry -> entry.getValue().bytesReceived + entry.getValue().bytesSent)
                .sum();
            
            return periodMs > 0 ? (double) byteCount / (periodMs / 1000.0) : 0;
        }
        
        public double getAverageProcessingDelay() {
            long totalDelays = processingDelays.sum();
            long delayCount = processingDelays.sum() > 0 ? processingDelays.sum() : 1;
            return (double) totalProcessingTime.sum() / delayCount;
        }
        
        @Override
        public String toString() {
            return String.format(
                "ConnectionMetrics{received=%d/%d bytes, sent=%d/%d bytes, errors=%d, delay=%.2fms}",
                messagesReceived.sum(), bytesReceived.sum(),
                messagesSent.sum(), bytesSent.sum(),
                errors.sum(),
                getAverageProcessingDelay()
            );
        }
    }
    
    /**
     * 全局指标类
     */
    public static class GlobalMetrics {
        // 连接统计
        public final AtomicInteger activeConnections = new AtomicInteger(0);
        public final LongAdder totalConnections = new LongAdder();
        
        // 消息统计
        public final LongAdder totalMessagesReceived = new LongAdder();
        public final LongAdder totalMessagesSent = new LongAdder();
        public final LongAdder totalBytesReceived = new LongAdder();
        public final LongAdder totalBytesSent = new LongAdder();
        
        // 错误统计
        public final LongAdder totalErrors = new LongAdder();
        
        // 延迟统计
        public final LongAdder totalProcessingTime = new LongAdder();
        
        // 历史统计
        private final ConcurrentHashMap<Long, GlobalMetricsSnapshot> history = new ConcurrentHashMap<>();
        
        public void collect(long timestamp) {
            GlobalMetricsSnapshot snapshot = new GlobalMetricsSnapshot(
                timestamp,
                activeConnections.get(),
                totalMessagesReceived.sum(),
                totalMessagesSent.sum(),
                totalBytesReceived.sum(),
                totalBytesSent.sum(),
                totalErrors.sum()
            );
            
            history.put(timestamp, snapshot);
            
            // 清理旧的历史数据（保留最近24小时）
            long cutoff = timestamp - 86400000; // 24小时
            history.keySet().removeIf(key -> key < cutoff);
        }
        
        public double getMessageThroughput(long periodMs) {
            long now = System.currentTimeMillis();
            long cutoff = now - periodMs;
            
            long messageCount = history.entrySet().stream()
                .filter(entry -> entry.getKey() >= cutoff)
                .mapToLong(entry -> entry.getValue().messagesReceived + entry.getValue().messagesSent)
                .sum();
            
            return periodMs > 0 ? (double) messageCount / (periodMs / 1000.0) : 0;
        }
        
        public double getByteThroughput(long periodMs) {
            long now = System.currentTimeMillis();
            long cutoff = now - periodMs;
            
            long byteCount = history.entrySet().stream()
                .filter(entry -> entry.getKey() >= cutoff)
                .mapToLong(entry -> entry.getValue().bytesReceived + entry.getValue().bytesSent)
                .sum();
            
            return periodMs > 0 ? (double) byteCount / (periodMs / 1000.0) : 0;
        }
        
        public double getErrorRate(long periodMs) {
            long now = System.currentTimeMillis();
            long cutoff = now - periodMs;
            
            long errorCount = history.entrySet().stream()
                .filter(entry -> entry.getKey() >= cutoff)
                .mapToLong(entry -> entry.getValue().errors)
                .sum();
            
            long messageCount = history.entrySet().stream()
                .filter(entry -> entry.getKey() >= cutoff)
                .mapToLong(entry -> entry.getValue().messagesReceived + entry.getValue().messagesSent)
                .sum();
            
            return messageCount > 0 ? (double) errorCount / messageCount * 100 : 0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "GlobalMetrics{connections=%d/%d, messages=%d/%d bytes, errors=%d, throughput=%.2f msgs/s}",
                activeConnections.get(), totalConnections.sum(),
                totalMessagesReceived.sum() + totalMessagesSent.sum(),
                totalBytesReceived.sum() + totalBytesSent.sum(),
                totalErrors.sum(),
                getMessageThroughput(60000) // 最近1分钟的消息吞吐量
            );
        }
    }
    
    /**
     * 服务器指标类
     */
    public static class ServerMetrics {
        // 服务器特定指标
        public final LongAdder requestsProcessed = new LongAdder();
        public final LongAdder requestsFailed = new LongAdder();
        public final LongAdder requestProcessingTime = new LongAdder();
        
        @Override
        public String toString() {
            return String.format(
                "ServerMetrics{processed=%d, failed=%d, avgTime=%.2fms}",
                requestsProcessed.sum(),
                requestsFailed.sum(),
                requestsProcessed.sum() > 0 ? 
                    (double) requestProcessingTime.sum() / requestsProcessed.sum() : 0
            );
        }
    }
    
    /**
     * 性能报告类
     */
    public static class PerformanceReport {
        public final long timestamp;
        public final GlobalMetrics globalMetrics;
        public final int connectionCount;
        public final String serverMetricsSummary;
        
        public PerformanceReport(long timestamp, GlobalMetrics globalMetrics,
                               int connectionCount, String serverMetricsSummary) {
            this.timestamp = timestamp;
            this.globalMetrics = globalMetrics;
            this.connectionCount = connectionCount;
            this.serverMetricsSummary = serverMetricsSummary;
        }
        
        public String getDetailedMetrics() {
            return String.format(
                "PerformanceReport{timestamp=%d, connections=%d, %s, servers=%s}",
                timestamp, connectionCount, globalMetrics, serverMetricsSummary
            );
        }
        
        @Override
        public String toString() {
            return String.format(
                "PerformanceReport[connections=%d, throughput=%.2f msgs/s, errorRate=%.2f%%]",
                connectionCount,
                globalMetrics.getMessageThroughput(60000),
                globalMetrics.getErrorRate(60000)
            );
        }
    }
    
    /**
     * 告警类
     */
    public static class Alert {
        public final long timestamp;
        public final String type;
        public final String message;
        public final String connectionId;
        public final AlertSeverity severity;
        
        public Alert(long timestamp, String type, String message, 
                    String connectionId, AlertSeverity severity) {
            this.timestamp = timestamp;
            this.type = type;
            this.message = message;
            this.connectionId = connectionId;
            this.severity = severity;
        }
        
        @Override
        public String toString() {
            return String.format(
                "Alert{type=%s, severity=%s, connection=%s, message=%s}",
                type, severity, connectionId, message
            );
        }
    }
    
    /**
     * 告警严重程度
     */
    public enum AlertSeverity {
        INFO, WARNING, ERROR, CRITICAL
    }
    
    /**
     * 指标快照
     */
    private static class MetricsSnapshot {
        public final long timestamp;
        public final long messagesReceived;
        public final long messagesSent;
        public final long bytesReceived;
        public final long bytesSent;
        public final long errors;
        public final long processingDelays;
        public final long maxProcessingDelay;
        
        public MetricsSnapshot(long timestamp, long messagesReceived, long messagesSent,
                              long bytesReceived, long bytesSent, long errors,
                              long processingDelays, long maxProcessingDelay) {
            this.timestamp = timestamp;
            this.messagesReceived = messagesReceived;
            this.messagesSent = messagesSent;
            this.bytesReceived = bytesReceived;
            this.bytesSent = bytesSent;
            this.errors = errors;
            this.processingDelays = processingDelays;
            this.maxProcessingDelay = maxProcessingDelay;
        }
    }
    
    /**
     * 全局指标快照
     */
    private static class GlobalMetricsSnapshot {
        public final long timestamp;
        public final int activeConnections;
        public final long messagesReceived;
        public final long messagesSent;
        public final long bytesReceived;
        public final long bytesSent;
        public final long errors;
        
        public GlobalMetricsSnapshot(long timestamp, int activeConnections,
                                   long messagesReceived, long messagesSent,
                                   long bytesReceived, long bytesSent, long errors) {
            this.timestamp = timestamp;
            this.activeConnections = activeConnections;
            this.messagesReceived = messagesReceived;
            this.messagesSent = messagesSent;
            this.bytesReceived = bytesReceived;
            this.bytesSent = bytesSent;
            this.errors = errors;
        }
    }
    
    /**
     * 监控配置类
     */
    public static class MonitorConfig {
        public long collectionIntervalMs = 5000;      // 收集间隔：5秒
        public long reportIntervalMs = 60000;         // 报告间隔：1分钟
        public long maxIdleTimeMs = 300000;           // 最大空闲时间：5分钟
        public int maxErrorsPerConnection = 10;       // 每个连接最大错误数
        public double memoryUsageThreshold = 80.0;    // 内存使用率阈值：80%
        public int maxThreadCount = 500;              // 最大线程数
        public boolean enableAlerting = true;         // 启用告警
        public boolean enableReporting = true;        // 启用报告
        
        @Override
        public String toString() {
            return String.format(
                "MonitorConfig{collection=%dms, report=%dms, idle=%dms, errors=%d}",
                collectionIntervalMs, reportIntervalMs, maxIdleTimeMs, maxErrorsPerConnection
            );
        }
    }
}