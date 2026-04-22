package javax.net.p2p.monitor;

import io.netty.buffer.ByteBuf;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import lombok.extern.slf4j.Slf4j;

/**
 * UDP性能监控和统计管理器
 * 
 * 主要功能：
 * 1. 实时性能指标收集和统计
 * 2. 连接和会话监控
 * 3. 丢包率、延迟、吞吐量监控
 * 4. 可靠性指标统计（重传率、确认率等）
 * 5. 网络状况分析和告警
 * 6. 性能趋势分析和预测
 * 
 * 监控指标：
 * 1. 基础指标：
 *    - 发送/接收消息数
 *    - 发送/接收字节数
 *    - 发送/接收速率（msg/s, bytes/s）
 *    - 平均延迟（RTT）
 *    
 * 2. 可靠性指标：
 *    - 丢包率
 *    - 重传率
 *    - ACK确认率
 *    - 乱序率
 *    
 * 3. 网络质量指标：
 *    - 延迟抖动（Jitter）
 *    - 带宽利用率
 *    - 网络拥塞程度
 *    
 * 4. 系统指标：
 *    - 内存使用
 *    - CPU使用
 *    - 线程池状态
 * 
 * @author CodeBuddy
 */
@Slf4j
public class UdpPerformanceMonitor {
    
    // 单例实例
    private static final AtomicReference<UdpPerformanceMonitor> instance = 
        new AtomicReference<>(new UdpPerformanceMonitor());
    
    // 监控统计锁
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    // 全局统计
    private final UdpGlobalStatistics globalStats = new UdpGlobalStatistics();
    
    // 会话统计（按远程地址）
    private final Map<InetSocketAddress, UdpSessionStatistics> sessionStats = 
        new ConcurrentHashMap<>();
    
    // 定时任务执行器
    private final ScheduledExecutorService scheduler = 
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "udp-monitor-scheduler");
            t.setDaemon(true);
            return t;
        });
    
    // 告警阈值
    private final UdpAlarmThresholds alarmThresholds = new UdpAlarmThresholds();
    
    // 构造函数
    private UdpPerformanceMonitor() {
        startMonitoring();
    }
    
    /**
     * 获取单例实例
     */
    public static UdpPerformanceMonitor getInstance() {
        return instance.get();
    }
    
    /**
     * 开始监控
     */
    private void startMonitoring() {
        // 每5秒收集一次统计数据
        scheduler.scheduleAtFixedRate(() -> {
            try {
                collectAndReportStatistics();
            } catch (Exception e) {
                log.error("收集UDP统计信息失败", e);
            }
        }, 5, 5, TimeUnit.SECONDS);
        
        // 每30秒检查告警
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkAlarms();
            } catch (Exception e) {
                log.error("检查UDP告警失败", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
        
        log.info("UDP性能监控器已启动");
    }
    
    /**
     * 记录发送消息
     */
    public void recordMessageSent(InetSocketAddress remoteAddress, ByteBuf message, long sequenceNumber) {
        lock.writeLock().lock();
        try {
            globalStats.messagesSent.incrementAndGet();
            globalStats.bytesSent.addAndGet(message.readableBytes());
            
            UdpSessionStatistics session = getSessionStats(remoteAddress);
            session.messagesSent.incrementAndGet();
            session.bytesSent.addAndGet(message.readableBytes());
            
            // 记录发送时间，用于计算RTT
            if (sequenceNumber > 0) {
                session.messageSentTimes.put(sequenceNumber, System.currentTimeMillis());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 记录接收消息
     */
    public void recordMessageReceived(InetSocketAddress remoteAddress, ByteBuf message, long sequenceNumber) {
        lock.writeLock().lock();
        try {
            globalStats.messagesReceived.incrementAndGet();
            globalStats.bytesReceived.addAndGet(message.readableBytes());
            
            UdpSessionStatistics session = getSessionStats(remoteAddress);
            session.messagesReceived.incrementAndGet();
            session.bytesReceived.addAndGet(message.readableBytes());
            
            // 记录接收时间，用于计算延迟
            if (sequenceNumber > 0) {
                Long sentTime = session.messageSentTimes.get(sequenceNumber);
                if (sentTime != null) {
                    long rtt = System.currentTimeMillis() - sentTime;
                    session.updateRtt(rtt);
                    session.messageSentTimes.remove(sequenceNumber);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 记录丢包事件
     */
    public void recordPacketLoss(InetSocketAddress remoteAddress, long sequenceNumber, String reason) {
        lock.writeLock().lock();
        try {
            globalStats.packetsLost.incrementAndGet();
            
            UdpSessionStatistics session = getSessionStats(remoteAddress);
            session.packetsLost.incrementAndGet();
            
            log.debug("UDP丢包: remote={}, seq={}, reason={}", remoteAddress, sequenceNumber, reason);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 记录重传事件
     */
    public void recordRetransmission(InetSocketAddress remoteAddress, long sequenceNumber, int retryCount) {
        lock.writeLock().lock();
        try {
            globalStats.retransmissions.incrementAndGet();
            
            UdpSessionStatistics session = getSessionStats(remoteAddress);
            session.retransmissions.incrementAndGet();
            session.updateRetransmissionStats(retryCount);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 记录ACK确认
     */
    public void recordAckReceived(InetSocketAddress remoteAddress, long sequenceNumber, long rtt) {
        lock.writeLock().lock();
        try {
            globalStats.acksReceived.incrementAndGet();
            
            UdpSessionStatistics session = getSessionStats(remoteAddress);
            session.acksReceived.incrementAndGet();
            session.updateRtt(rtt);
            
            // 移除已确认的消息发送时间记录
            session.messageSentTimes.remove(sequenceNumber);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 记录乱序消息
     */
    public void recordOutOfOrder(InetSocketAddress remoteAddress, long expectedSeq, long receivedSeq) {
        lock.writeLock().lock();
        try {
            globalStats.outOfOrderMessages.incrementAndGet();
            
            UdpSessionStatistics session = getSessionStats(remoteAddress);
            session.outOfOrderMessages.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 获取会话统计信息
     */
    private UdpSessionStatistics getSessionStats(InetSocketAddress remoteAddress) {
        return sessionStats.computeIfAbsent(remoteAddress, 
            addr -> new UdpSessionStatistics(addr));
    }
    
    /**
     * 收集和报告统计信息
     */
    private void collectAndReportStatistics() {
        lock.readLock().lock();
        try {
            // 计算速率（每秒）
            long currentTime = System.currentTimeMillis();
            long elapsedSeconds = (currentTime - globalStats.lastReportTime) / 1000;
            
            if (elapsedSeconds > 0) {
                long sentRate = (globalStats.messagesSent.get() - globalStats.lastMessagesSent) / elapsedSeconds;
                long ackRate = (globalStats.acksReceived.get() - globalStats.lastMessagesReceived) / elapsedSeconds;
                long receivedRate = (globalStats.messagesReceived.get() - globalStats.lastMessagesReceived) / elapsedSeconds;
                
                globalStats.lastMessagesSent = globalStats.messagesSent.get();
                globalStats.lastMessagesReceived = globalStats.messagesReceived.get();
                globalStats.lastReportTime = currentTime;
                
                // 记录当前速率
                globalStats.messageSendRate = sentRate;
                globalStats.messageAckRate = ackRate;
                globalStats.messageReceiveRate = receivedRate;
                
                // 记录到历史数据
                if (globalStats.history.size() >= 100) {
                    globalStats.history.pollFirstEntry();
                }
                globalStats.history.put(currentTime, new UdpSnapshot(
                    sentRate, receivedRate, 
                    globalStats.messagesSent.get(), globalStats.messagesReceived.get(),
                    globalStats.packetsLost.get(), globalStats.retransmissions.get(),
                    globalStats.getAverageRtt()
                ));
            }
            
            // 定期打印统计摘要
            if (System.currentTimeMillis() - globalStats.lastSummaryTime > 60000) {
                printSummary();
                globalStats.lastSummaryTime = System.currentTimeMillis();
            }
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 打印统计摘要
     */
    private void printSummary() {
        lock.readLock().lock();
        try {
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            String timestamp = dtf.format(LocalDateTime.now());
            
            log.info("\n=========================================");
            log.info("UDP性能统计摘要 [{}]", timestamp);
            log.info("=========================================");
            log.info("全局统计:");
            log.info("  发送消息: {} ({} msg/s)", 
                globalStats.messagesSent.get(), globalStats.messageSendRate);
            log.info("  发送Ack: {} ({} msg/s)", 
                globalStats.acksReceived.get(), globalStats.messageSendRate);
            log.info("  接收消息: {} ({} msg/s)", 
                globalStats.messagesReceived.get(), globalStats.messageReceiveRate);
            log.info("  发送字节: {}", formatBytes(globalStats.bytesSent.get()));
            log.info("  接收字节: {}", formatBytes(globalStats.bytesReceived.get()));
            log.info("  丢包数: {} ({}%)", 
                globalStats.packetsLost.get(), 
                calculateLossRate(globalStats.messagesSent.get(), globalStats.packetsLost.get()));
            log.info("  重传数: {} ({}%)", 
                globalStats.retransmissions.get(),
                calculateLossRate(globalStats.messagesSent.get(), globalStats.retransmissions.get()));
            log.info("  平均RTT: {} ms", globalStats.getAverageRtt());
            log.info("  乱序消息: {}", globalStats.outOfOrderMessages.get());
            log.info("  活跃会话: {}", sessionStats.size());
            
            // 打印前5个会话的统计
            if (!sessionStats.isEmpty()) {
                log.info("  活跃会话统计:");
                sessionStats.entrySet().stream()
                    .limit(5)
                    .forEach(entry -> {
                        UdpSessionStatistics stats = entry.getValue();
                        log.info("    {}: 发送={}, 发送Ack={}, 接收={}, 丢包率={}%, RTT={}ms", 
                            entry.getKey(),
                            stats.messagesSent.get(),
                            stats.acksReceived.get(),
                            stats.messagesReceived.get(),
                            calculateLossRate(stats.messagesSent.get(), stats.packetsLost.get()),
                            stats.getAverageRtt());
                    });
            }
            log.info("=========================================\n");
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 检查告警
     */
    private void checkAlarms() {
        lock.readLock().lock();
        try {
            // 检查丢包率告警
            double lossRate = calculateLossRate(
                globalStats.messagesSent.get(), 
                globalStats.packetsLost.get());
            
            if (lossRate > alarmThresholds.packetLossWarning) {
                if (lossRate > alarmThresholds.packetLossCritical) {
                    triggerAlarm("CRITICAL", 
                        String.format("UDP丢包率过高: %.2f%%", lossRate));
                } else {
                    triggerAlarm("WARNING", 
                        String.format("UDP丢包率偏高: %.2f%%", lossRate));
                }
            }
            
            // 检查重传率告警
            double retransmissionRate = calculateLossRate(
                globalStats.messagesSent.get(), 
                globalStats.retransmissions.get());
            
            if (retransmissionRate > alarmThresholds.retransmissionWarning) {
                triggerAlarm("WARNING", 
                    String.format("UDP重传率偏高: %.2f%%", retransmissionRate));
            }
            
            // 检查RTT告警
            long avgRtt = globalStats.getAverageRtt();
            if (avgRtt > alarmThresholds.rttWarning) {
                triggerAlarm("WARNING", 
                    String.format("UDP平均RTT偏高: %dms", avgRtt));
            }
            
            // 检查吞吐量告警
            if (globalStats.messageSendRate < alarmThresholds.minSendRate) {
                triggerAlarm("WARNING", 
                    String.format("UDP发送速率过低: %d msg/s", globalStats.messageSendRate));
            }
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 触发告警
     */
    private void triggerAlarm(String level, String message) {
        log.warn("[UDP告警 {}] {}", level, message);
        // 这里可以添加邮件、短信等告警通知
    }
    
    /**
     * 计算丢包率
     */
    private double calculateLossRate(long sent, long lost) {
        if (sent == 0) return 0.0;
        return (lost * 100.0) / sent;
    }
    
    /**
     * 格式化字节数
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    /**
     * 获取性能报告
     */
    public String getPerformanceReport() {
        lock.readLock().lock();
        try {
            StringBuilder report = new StringBuilder();
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            
            report.append("UDP性能监控报告\n");
            report.append("生成时间: ").append(dtf.format(LocalDateTime.now())).append("\n");//acksReceived messageAckRate
            report.append("=========================================\n");
            report.append("全局性能指标:\n");
            report.append(String.format("  发送消息总数: %d\n", globalStats.messagesSent.get()));
            report.append(String.format("  发送ACK总数: %d\n", globalStats.acksReceived.get()));
            report.append(String.format("  接收消息总数: %d\n", globalStats.messagesReceived.get()));
            report.append(String.format("  当前发送速率: %d msg/s\n", globalStats.messageSendRate));
            report.append(String.format("  当前ACK速率: %d msg/s\n", globalStats.messageAckRate));
            report.append(String.format("  当前接收速率: %d msg/s\n", globalStats.messageReceiveRate));
            report.append(String.format("  总发送字节数: %s\n", formatBytes(globalStats.bytesSent.get())));
            report.append(String.format("  总接收字节数: %s\n", formatBytes(globalStats.bytesReceived.get())));
            report.append(String.format("  丢包率: %.2f%%\n", 
                calculateLossRate(globalStats.messagesSent.get(), globalStats.packetsLost.get())));
            report.append(String.format("  重传率: %.2f%%\n", 
                calculateLossRate(globalStats.messagesSent.get(), globalStats.retransmissions.get())));
            report.append(String.format("  平均RTT: %d ms\n", globalStats.getAverageRtt()));
            report.append(String.format("  活跃会话数: %d\n", sessionStats.size()));
            
            return report.toString();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 关闭监控器
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("UDP性能监控器已关闭");
    }
    
    // ========== 内部数据结构 ==========
    
    /**
     * UDP全局统计信息
     */
    private static class UdpGlobalStatistics {
        // 消息计数
        final AtomicLong messagesSent = new AtomicLong(0);
        final AtomicLong messagesReceived = new AtomicLong(0);
        final AtomicLong bytesSent = new AtomicLong(0);
        final AtomicLong bytesReceived = new AtomicLong(0);
        
        // 可靠性指标
        final AtomicLong packetsLost = new AtomicLong(0);
        final AtomicLong retransmissions = new AtomicLong(0);
        final AtomicLong acksReceived = new AtomicLong(0);
        final AtomicLong outOfOrderMessages = new AtomicLong(0);
        
        // 速率统计
        volatile long messageSendRate = 0;
        volatile long messageAckRate = 0;
        volatile long messageReceiveRate = 0;
        
        // RTT统计
        final AtomicLong totalRtt = new AtomicLong(0);
        final AtomicInteger rttCount = new AtomicInteger(0);
        
        // 历史数据（最近100个采样点）
        final ConcurrentSkipListMap<Long, UdpSnapshot> history = new ConcurrentSkipListMap<>();
        
        // 时间戳
        volatile long lastReportTime = System.currentTimeMillis();
        volatile long lastSummaryTime = System.currentTimeMillis();
        volatile long lastMessagesSent = 0;
        volatile long lastMessagesReceived = 0;
        
        
        long getAverageRtt() {
            int count = rttCount.get();
            if (count == 0) return 0;
            return totalRtt.get() / count;
        }
        
        void updateRtt(long rtt) {
            totalRtt.addAndGet(rtt);
            rttCount.incrementAndGet();
        }
    }
    
    /**
     * UDP会话统计信息
     */
    private static class UdpSessionStatistics {
        final InetSocketAddress remoteAddress;
        
        // 消息计数
        final AtomicLong messagesSent = new AtomicLong(0);
        final AtomicLong messagesReceived = new AtomicLong(0);
        final AtomicLong bytesSent = new AtomicLong(0);
        final AtomicLong bytesReceived = new AtomicLong(0);
        
        // 可靠性指标
        final AtomicLong packetsLost = new AtomicLong(0);
        final AtomicLong retransmissions = new AtomicLong(0);
        final AtomicLong acksReceived = new AtomicLong(0);
        final AtomicLong outOfOrderMessages = new AtomicLong(0);
        
        // 重传统计
        final AtomicInteger maxRetryCount = new AtomicInteger(0);
        final AtomicLong totalRetryCount = new AtomicLong(0);
        final AtomicInteger retryCount = new AtomicInteger(0);
        
        // RTT统计
        final AtomicLong totalRtt = new AtomicLong(0);
        final AtomicInteger rttCount = new AtomicInteger(0);
        
        // 消息发送时间记录（用于计算RTT）
        final Map<Long, Long> messageSentTimes = new ConcurrentHashMap<>();
        
        UdpSessionStatistics(InetSocketAddress remoteAddress) {
            this.remoteAddress = remoteAddress;
        }
        
        long getAverageRtt() {
            int count = rttCount.get();
            if (count == 0) return 0;
            return totalRtt.get() / count;
        }
        
        void updateRtt(long rtt) {
            totalRtt.addAndGet(rtt);
            rttCount.incrementAndGet();
        }
        
        void updateRetransmissionStats(int retryCount) {
            this.retryCount.incrementAndGet();
            totalRetryCount.addAndGet(retryCount);
            if (retryCount > maxRetryCount.get()) {
                maxRetryCount.set(retryCount);
            }
        }
    }
    
    /**
     * UDP快照数据
     */
    private static class UdpSnapshot {
        final long timestamp;
        final long sendRate;
        final long receiveRate;
        final long totalSent;
        final long totalReceived;
        final long packetsLost;
        final long retransmissions;
        final long avgRtt;
        
        UdpSnapshot(long sendRate, long receiveRate, 
                   long totalSent, long totalReceived,
                   long packetsLost, long retransmissions,
                   long avgRtt) {
            this.timestamp = System.currentTimeMillis();
            this.sendRate = sendRate;
            this.receiveRate = receiveRate;
            this.totalSent = totalSent;
            this.totalReceived = totalReceived;
            this.packetsLost = packetsLost;
            this.retransmissions = retransmissions;
            this.avgRtt = avgRtt;
        }
    }
    
    /**
     * UDP告警阈值
     */
    private static class UdpAlarmThresholds {
        // 丢包率阈值（%）
        final double packetLossWarning = 2.0;      // 2%告警
        final double packetLossCritical = 5.0;     // 5%严重告警
        
        // 重传率阈值（%）
        final double retransmissionWarning = 3.0;  // 3%告警
        
        // RTT阈值（ms）
        final long rttWarning = 500;              // 500ms告警
        
        // 吞吐量阈值
        final long minSendRate = 10;              // 最低发送速率10msg/s
    }
}