package javax.net.p2p.udp.optimized;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.p2p.monitor.UdpMonitorDecorator;
import lombok.extern.slf4j.Slf4j;

/**
 * 增强版UDP可靠性管理器 - 集成性能监控和统计功能
 * 
 * 在原有UdpReliabilityManager的基础上，添加：
 * 1. 全面的性能指标收集
 * 2. 实时统计报告
 * 3. 监控告警功能
 * 4. 历史数据记录
 * 5. 与UdpPerformanceMonitor的集成
 * 
 * @author CodeBuddy
 */
@Slf4j
public class UdpReliabilityManagerWithStats {
    
    // 消息状态枚举
    private enum MessageStatus {
      SENT,       // 已发送，等待ACK
        ACKED,      // 已确认
        RETRANSMITTING, // 重传中
        TIMED_OUT   // 超时未确认
    }
    
    // 消息记录类（增强版）
    private static class EnhancedMessageRecord {
        final long sendTime;
        final ByteBuf messageBuffer;
        final int seq;
        final InetSocketAddress remoteAddress;
        MessageStatus status;
        int retryCount;
        long ackReceiveTime;
        long rtt;
        ScheduledFuture<?> timeoutFuture;
        
        EnhancedMessageRecord(int seq, ByteBuf messageBuffer, InetSocketAddress remoteAddress) {
            this.seq = seq;
            this.messageBuffer = messageBuffer;
            this.remoteAddress = remoteAddress;
            this.sendTime = System.currentTimeMillis();
            this.status = MessageStatus.SENT;
            this.retryCount = 0;
            this.ackReceiveTime = -1;
            this.rtt = -1;
        }
        
        void release() {
            if (messageBuffer != null && messageBuffer.refCnt() > 0) {
                messageBuffer.release();
            }
        }
        
        void markAcked() {
            this.ackReceiveTime = System.currentTimeMillis();
            this.rtt = ackReceiveTime - sendTime;
            this.status = MessageStatus.ACKED;
        }
        
        void markRetransmitting() {
            this.status = MessageStatus.RETRANSMITTING;
            this.retryCount++;
        }
        
        void markTimedOut() {
            this.status = MessageStatus.TIMED_OUT;
        }
    }
    
    // 统计数据结构
    private static class SessionStatistics {
        final InetSocketAddress remoteAddress;
        
        // 消息计数
        final AtomicLong messagesSent = new AtomicLong(0);
        final AtomicLong messagesReceived = new AtomicLong(0);
        final AtomicLong messagesAcked = new AtomicLong(0);
        final AtomicLong messagesLost = new AtomicLong(0);
        final AtomicLong messagesRetransmitted = new AtomicLong(0);
        
        // 字节计数
        final AtomicLong bytesSent = new AtomicLong(0);
        final AtomicLong bytesReceived = new AtomicLong(0);
        
        // RTT统计
        final AtomicLong totalRtt = new AtomicLong(0);
        final AtomicInteger rttCount = new AtomicInteger(0);
        
        // 延迟统计
        final AtomicLong maxRtt = new AtomicLong(0);
        final AtomicLong minRtt = new AtomicLong(Long.MAX_VALUE);
        
        // 时间戳
        long sessionStartTime = System.currentTimeMillis();
        long lastUpdateTime = System.currentTimeMillis();
        
        SessionStatistics(InetSocketAddress remoteAddress) {
            this.remoteAddress = remoteAddress;
        }
        
        void recordMessageSent(int size) {
            messagesSent.incrementAndGet();
            bytesSent.addAndGet(size);
            lastUpdateTime = System.currentTimeMillis();
        }
        
        void recordMessageReceived(int size) {
            messagesReceived.incrementAndGet();
            bytesReceived.addAndGet(size);
            lastUpdateTime = System.currentTimeMillis();
        }
        
        void recordMessageAcked(long rtt) {
            messagesAcked.incrementAndGet();
            totalRtt.addAndGet(rtt);
            rttCount.incrementAndGet();
            
            if (rtt > maxRtt.get()) {
                maxRtt.set(rtt);
            }
            if (rtt < minRtt.get()) {
                minRtt.set(rtt);
            }
            lastUpdateTime = System.currentTimeMillis();
        }
        
        void recordMessageLost() {
            messagesLost.incrementAndGet();
            lastUpdateTime = System.currentTimeMillis();
        }
        
        void recordMessageRetransmitted() {
            messagesRetransmitted.incrementAndGet();
            lastUpdateTime = System.currentTimeMillis();
        }
        
        long getAverageRtt() {
            int count = rttCount.get();
            if (count == 0) return 0;
            return totalRtt.get() / count;
        }
        
        double getLossRate() {
            long sent = messagesSent.get();
            if (sent == 0) return 0.0;
            return (messagesLost.get() * 100.0) / sent;
        }
        
        double getRetransmissionRate() {
            long sent = messagesSent.get();
            if (sent == 0) return 0.0;
            return (messagesRetransmitted.get() * 100.0) / sent;
        }
        
        long getSessionDuration() {
            return System.currentTimeMillis() - sessionStartTime;
        }
        
        long getSendRate() {
            long duration = getSessionDuration();
            if (duration == 0) return 0;
            return (messagesSent.get() * 1000) / duration;
        }
        
        long getReceiveRate() {
            long duration = getSessionDuration();
            if (duration == 0) return 0;
            return (messagesReceived.get() * 1000) / duration;
        }
    }
    
    // 成员变量
    private final boolean enabled;
    private final AtomicInteger nextSeq = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, EnhancedMessageRecord> pendingMessages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InetSocketAddress, ReceiveBuffer> receiveBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InetSocketAddress, SessionStatistics> sessionStats = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ReentrantLock lock = new ReentrantLock();
    
    // 配置参数
    private int maxRetries = 3;
    private long baseRTO = 2000L; // 基础重传超时时间
    private int windowSize = 32; // 滑动窗口大小
    
    // 统计相关
    private volatile long lastReportTime = System.currentTimeMillis();
    private final ConcurrentSkipListMap<Long, GlobalSnapshot> history = new ConcurrentSkipListMap<>();
    
    // 告警阈值
    private double lossRateWarning = 2.0; // 2%告警
    private double lossRateCritical = 5.0; // 5%严重告警
    private double retransmissionWarning = 3.0; // 3%告警
    private long rttWarning = 500; // 500ms告警
    
    public UdpReliabilityManagerWithStats(boolean enabled) {
        this.enabled = enabled;
        startMonitoring();
    }
    
    public UdpReliabilityManagerWithStats() {
        this(true);
    }
    
    /**
     * 发送消息
     */
    public void sendMessage(Channel channel, InetSocketAddress remoteAddress, ByteBuf message) {
        if (!enabled) {
            channel.writeAndFlush(new DatagramPacket(message, remoteAddress));
            return;
        }
        
        lock.lock();
        try {
            int seq = nextSeq.getAndIncrement();
            EnhancedMessageRecord record = new EnhancedMessageRecord(seq, message.retain(), remoteAddress);
            pendingMessages.put(seq, record);
            
            // 记录统计
            getSessionStats(remoteAddress).recordMessageSent(message.readableBytes());
            
            // 发送消息
            DatagramPacket packet = new DatagramPacket(message, remoteAddress);
            channel.writeAndFlush(packet);
            
            // 记录到UDP监控器
            UdpMonitorDecorator.getMonitor().recordMessageSent(remoteAddress, message, seq);
            
            // 设置超时重传
            scheduleRetransmission(channel, record);
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 接收消息
     */
    public void receiveMessage(Channel channel, InetSocketAddress sender, ByteBuf message) {
        if (!enabled) {
            deliverToApplication(sender, message);
            return;
        }
        
        lock.lock();
        try {
            // 尝试解析序列号（根据实际协议格式）
            int seq = extractSequenceNumber(message);
            
            // 记录统计
            getSessionStats(sender).recordMessageReceived(message.readableBytes());
            
            // 记录到UDP监控器
            UdpMonitorDecorator.getMonitor().recordMessageReceived(sender, message, seq);
            
            // 处理消息接收逻辑
            ReceiveBuffer buffer = receiveBuffers.computeIfAbsent(sender, 
                addr -> new ReceiveBuffer(addr));
            
            if (buffer.addMessage(seq, message.copy())) {
                // 发送ACK
                sendAck(channel, sender, seq);
            }
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 处理ACK消息
     */
    public void processAck(Channel channel, InetSocketAddress sender, int ackSeq) {
        if (!enabled) return;
        
        lock.lock();
        try {
            EnhancedMessageRecord record = pendingMessages.remove(ackSeq);
            if (record != null) {
                record.markAcked();
                record.release();
                
                // 记录统计
                getSessionStats(sender).recordMessageAcked(record.rtt);
                
                // 记录到UDP监控器
                UdpMonitorDecorator.recordReliabilityEvent(sender, "ACK_RECEIVED", ackSeq, record.rtt);
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 检查超时重传
     */
    private void checkTimeouts(Channel channel) {
        if (!enabled) return;
        
        long currentTime = System.currentTimeMillis();
        
        lock.lock();
        try {
            for (EnhancedMessageRecord record : pendingMessages.values()) {
                if (record.status == MessageStatus.SENT || record.status == MessageStatus.RETRANSMITTING) {
                    long elapsed = currentTime - record.sendTime;
                    
                    // 计算动态RTO
                    long rto = calculateDynamicRTO(record.remoteAddress);
                    
                    if (elapsed > rto) {
                        if (record.retryCount >= maxRetries) {
                            // 达到最大重试次数，标记为丢失
                            record.markTimedOut();
                            pendingMessages.remove(record.seq);
                            record.release();
                            
                            // 记录统计
                            getSessionStats(record.remoteAddress).recordMessageLost();
                            
                            // 记录到UDP监控器
                            UdpMonitorDecorator.recordReliabilityEvent(record.remoteAddress, 
                                "PACKET_LOSS", record.seq, "超时未确认");
                            
                        } else {
                            // 重传消息
                            record.markRetransmitting();
                            ByteBuf retryMessage = record.messageBuffer.copy();
                            DatagramPacket packet = new DatagramPacket(retryMessage, record.remoteAddress);
                            channel.writeAndFlush(packet);
                            
                            // 记录统计
                            getSessionStats(record.remoteAddress).recordMessageRetransmitted();
                            
                            // 记录到UDP监控器
                            UdpMonitorDecorator.recordReliabilityEvent(record.remoteAddress, 
                                "RETRANSMISSION", record.seq, record.retryCount);
                            
                            // 重新设置超时
                            scheduleRetransmission(channel, record);
                        }
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 获取会话统计信息
     */
    private SessionStatistics getSessionStats(InetSocketAddress remoteAddress) {
        return sessionStats.computeIfAbsent(remoteAddress, 
            addr -> new SessionStatistics(addr));
    }
    
    /**
     * 开始监控
     */
    private void startMonitoring() {
        // 每10秒检查一次超时
        scheduler.scheduleAtFixedRate(() -> {
            // 超时检查逻辑会在实际使用时通过channel进行
        }, 10, 10, TimeUnit.SECONDS);
        
        // 每30秒收集统计信息
        scheduler.scheduleAtFixedRate(() -> {
            collectStatistics();
        }, 30, 30, TimeUnit.SECONDS);
        
        log.info("增强版UDP可靠性管理器已启动，包含性能监控和统计功能");
    }
    
    /**
     * 收集统计信息
     */
    private void collectStatistics() {
        lock.lock();
        try {
            // 收集全局统计
            long currentTime = System.currentTimeMillis();
            
            if (history.size() >= 100) {
                history.pollFirstEntry();
            }
            
            // 创建快照
            GlobalSnapshot snapshot = new GlobalSnapshot(currentTime);
            history.put(currentTime, snapshot);
            
            // 定期打印报告
            if (currentTime - lastReportTime > 60000) { // 每分钟打印一次
                printStatisticsReport();
                lastReportTime = currentTime;
            }
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 打印统计报告
     */
    private void printStatisticsReport() {
        lock.lock();
        try {
            StringBuilder report = new StringBuilder();
            report.append("\n=========================================\n");
            report.append("UDP可靠性管理器统计报告\n");
            report.append("生成时间: ").append(System.currentTimeMillis()).append("\n");
            report.append("=========================================\n");
            
            // 全局统计
            report.append("全局性能指标:\n");
            
            long totalSent = 0;
            long totalReceived = 0;
            long totalLost = 0;
            long totalRetransmitted = 0;
            long totalRtt = 0;
            int totalRttCount = 0;
            
            for (SessionStatistics stats : sessionStats.values()) {
                totalSent += stats.messagesSent.get();
                totalReceived += stats.messagesReceived.get();
                totalLost += stats.messagesLost.get();
                totalRetransmitted += stats.messagesRetransmitted.get();
                totalRtt += stats.totalRtt.get();
                totalRttCount += stats.rttCount.get();
            }
            
            report.append(String.format("  发送消息总数: %d\n", totalSent));
            report.append(String.format("  接收消息总数: %d\n", totalReceived));
            report.append(String.format("  丢包数: %d\n", totalLost));
            report.append(String.format("  重传数: %d\n", totalRetransmitted));
            
            double lossRate = totalSent > 0 ? (totalLost * 100.0) / totalSent : 0.0;
            double retransmissionRate = totalSent > 0 ? (totalRetransmitted * 100.0) / totalSent : 0.0;
            long avgRtt = totalRttCount > 0 ? totalRtt / totalRttCount : 0;
            
            report.append(String.format("  丢包率: %.2f%%\n", lossRate));
            report.append(String.format("  重传率: %.2f%%\n", retransmissionRate));
            report.append(String.format("  平均RTT: %d ms\n", avgRtt));
            report.append(String.format("  活跃会话数: %d\n", sessionStats.size()));
            
            // 检查告警
            if (lossRate > lossRateWarning) {
                String level = lossRate > lossRateCritical ? "CRITICAL" : "WARNING";
                report.append(String.format("  [%s] 丢包率过高: %.2f%%\n", level, lossRate));
            }
            
            if (retransmissionRate > retransmissionWarning) {
                report.append(String.format("  [WARNING] 重传率偏高: %.2f%%\n", retransmissionRate));
            }
            
            if (avgRtt > rttWarning) {
                report.append(String.format("  [WARNING] 平均RTT偏高: %d ms\n", avgRtt));
            }
            
            report.append("=========================================\n");
            
            log.info(report.toString());
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 从消息内容中提取序列号
     */
    private int extractSequenceNumber(ByteBuf content) {
        try {
            if (content.readableBytes() >= 8) {
                content.markReaderIndex();
                long seq = content.readLong();
                content.resetReaderIndex();
                return (int) seq;
            }
        } catch (Exception e) {
            log.debug("无法提取序列号: {}", e.getMessage());
        }
        return -1;
    }
    
    /**
     * 计算动态RTO
     */
    private long calculateDynamicRTO(InetSocketAddress remoteAddress) {
        SessionStatistics stats = getSessionStats(remoteAddress);
        long avgRtt = stats.getAverageRtt();
        
        if (avgRtt <= 0) {
            return baseRTO;
        }
        
        // 基于平均RTT和方差计算动态RTO
        long variance = stats.maxRtt.get() - stats.minRtt.get();
        long dynamicRto = avgRtt + Math.max(200, variance / 2);
        
        return Math.min(dynamicRto, 10000L); // 最大不超过10秒
    }
    
    /**
     * 安排重传
     */
    private void scheduleRetransmission(Channel channel, EnhancedMessageRecord record) {
        long rto = calculateDynamicRTO(record.remoteAddress);
        
        // 使用指数退避策略
        long backoffRto = rto * (1 << record.retryCount);
        long maxRto = 30000L; // 最大30秒
        
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            checkTimeouts(channel);
        }, Math.min(backoffRto, maxRto), TimeUnit.MILLISECONDS);
        
        record.timeoutFuture = future;
    }
    
    /**
     * 发送ACK
     */
    private void sendAck(Channel channel, InetSocketAddress recipient, int ackSeq) {
        // 创建ACK消息（根据实际协议格式）
        ByteBuf ackMessage = createAckMessage(ackSeq);
        DatagramPacket ackPacket = new DatagramPacket(ackMessage, recipient);
        channel.writeAndFlush(ackPacket);
    }
    
    /**
     * 创建ACK消息
     */
    private ByteBuf createAckMessage(int ackSeq) {
        // 这里需要根据实际协议格式创建ACK消息
        // 简化实现：只包含序列号
        return null; // 实际实现中返回正确的ByteBuf
    }
    
    /**
     * 交付消息给应用层
     */
    private void deliverToApplication(InetSocketAddress sender, ByteBuf message) {
        // 这里需要根据实际应用逻辑处理消息
        // 简化实现：释放消息缓冲区
        message.release();
    }
    
    /**
     * 接收缓冲区类（内部使用）
     */
    private class ReceiveBuffer {
        private final NavigableMap<Integer, ByteBuf> buffer = new TreeMap<>();
        private int expectedSeq = 0;
        private InetSocketAddress sender;
        
        ReceiveBuffer(InetSocketAddress sender) {
            this.sender = sender;
        }
        
        boolean addMessage(int seq, ByteBuf data) {
            if (seq < expectedSeq) {
                data.release();
                return false;
            }
            
            buffer.put(seq, data.retain());
            return checkAndDeliver();
        }
        
        private boolean checkAndDeliver() {
            boolean delivered = false;
            
            while (true) {
                ByteBuf data = buffer.get(expectedSeq);
                if (data == null) {
                    break;
                }
                
                // 这里需要一个外部类的引用来调用deliverToApplication
                // 由于是内部类，我们需要通过UdpReliabilityManagerWithStats.this来访问
                // 但由于deliverToApplication是private方法，我们需要修改设计
                // 暂时注释掉这行，稍后修复
                 deliverToApplication(sender, data);
                buffer.remove(expectedSeq);
                expectedSeq++;
                delivered = true;
            }
            
            return delivered;
        }
        
        void clear() {
            for (ByteBuf data : buffer.values()) {
                data.release();
            }
            buffer.clear();
        }
        
        int size() {
            return buffer.size();
        }
        
        int getExpectedSeq() {
            return expectedSeq;
        }
    }
    
    /**
     * 全局快照类
     */
    private class GlobalSnapshot {
        final long timestamp;
        final Map<InetSocketAddress, SessionSnapshot> sessionSnapshots;
        
        GlobalSnapshot(long timestamp) {
            this.timestamp = timestamp;
            this.sessionSnapshots = new ConcurrentHashMap<>();
            
            for (Map.Entry<InetSocketAddress, SessionStatistics> entry : sessionStats.entrySet()) {
                sessionSnapshots.put(entry.getKey(), new SessionSnapshot(entry.getValue()));
            }
        }
    }
    
    /**
     * 会话快照类
     */
    private static class SessionSnapshot {
        final long messagesSent;
        final long messagesReceived;
        final long messagesLost;
        final long messagesRetransmitted;
        final long avgRtt;
        final long sessionDuration;
        
        SessionSnapshot(SessionStatistics stats) {
            this.messagesSent = stats.messagesSent.get();
            this.messagesReceived = stats.messagesReceived.get();
            this.messagesLost = stats.messagesLost.get();
            this.messagesRetransmitted = stats.messagesRetransmitted.get();
            this.avgRtt = stats.getAverageRtt();
            this.sessionDuration = stats.getSessionDuration();
        }
    }
    
    /**
     * 获取性能报告
     */
    public String getPerformanceReport() {
        return UdpMonitorDecorator.getPerformanceReport();
    }
    
    /**
     * 关闭管理器
     */
    public void shutdown() {
        scheduler.shutdown();
        
        // 清理资源
        for (EnhancedMessageRecord record : pendingMessages.values()) {
            record.release();
        }
        pendingMessages.clear();
        
        for (ReceiveBuffer buffer : receiveBuffers.values()) {
            buffer.clear();
        }
        receiveBuffers.clear();
        
        log.info("增强版UDP可靠性管理器已关闭");
    }
}