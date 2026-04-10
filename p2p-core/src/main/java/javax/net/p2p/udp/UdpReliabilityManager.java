package javax.net.p2p.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
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
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.P2PClientUdp;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * UDP可靠性管理器 - 为UDP协议提供可靠传输保障
 * 
 * 主要功能：
 * 1. 序列号管理：生成和管理消息序列号
 * 2. ACK确认机制：发送确认和等待确认
 * 3. 超时重传：检测未确认消息并重传
 * 4. 乱序处理：缓存和重组乱序到达的消息
 * 5. 流量控制和拥塞控制：基于网络状况的动态调整
 * 
 * 设计原理：
 * - 基于选择性重传（Selective Repeat）协议
 * - 滑动窗口控制并发发送数量
 * - 指数退避重传策略
 * - 自适应超时时间调整
 * - TCP友好的拥塞控制（基于RTT和丢包率）
 * 
 * 消息状态：
 * 1. SENT: 已发送，等待ACK
 * 2. ACKED: 已确认，可以清理
 * 3. RETRANSMITTING: 重传中
 * 4. TIMED_OUT: 超时未确认
 * 
 * @author karl
 */
@Slf4j
public class UdpReliabilityManager {

    void sendAck(Channel channel, InetSocketAddress sender, int seq) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    int sendReliableMessage(Channel channel, InetSocketAddress remoteAddress, P2PWrapper<?> message) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    void sendUnreliableMessage(Channel channel, InetSocketAddress remoteAddress, P2PWrapper<?> message) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }
    
    // 消息状态枚举
    private enum MessageStatus {
        SENT,       // 已发送，等待ACK
        ACKED,      // 已确认
        RETRANSMITTING, // 重传中
        TIMED_OUT   // 超时未确认
    }
    
    // 消息记录类
    private static class MessageRecord {
        final long sendTime;
        //final ByteBuf message;
        
        P2PWrapper<?> message;
        final int seq;
        final InetSocketAddress remoteAddress;
        MessageStatus status;
        int retryCount;
        ScheduledFuture<?> timeoutFuture;
        
        MessageRecord(int seq, P2PWrapper<?> message, InetSocketAddress remoteAddress) {
            this.seq = seq;
            this.message = message;
            this.remoteAddress = remoteAddress;
            this.sendTime = System.currentTimeMillis();
            this.status = MessageStatus.SENT;
            this.retryCount = 0;
        }
        
        void release() {
            if (message != null ) {
                message.recycle();
            }
        }
    }
    
    // 接收端消息缓存
    private class ReceiveBuffer {
        private final NavigableMap<Integer, ByteBuf> buffer = new TreeMap<>();
        private int expectedSeq = 0; // 期望接收的下一个序列号
        
        /**
         * 添加接收到的消息
         * @param seq 序列号
         * @param data 消息数据
         * @return 是否可以交付连续的消息给上层
         */
        boolean addMessage(int seq, ByteBuf data) {
            if (seq < expectedSeq) {
                // 重复的旧消息，直接丢弃
                data.release();
                return false;
            }
            
            // 缓存消息
            buffer.put(seq, data.retain());
            
            // 检查是否可以交付连续的消息
            return checkAndDeliver();
        }
        
        /**
         * 检查并交付连续的消息
         * @return 是否交付了消息
         */
        private boolean checkAndDeliver() {
            boolean delivered = false;
            
            while (true) {
                ByteBuf data = buffer.get(expectedSeq);
                if (data == null) {
                    break; // 缺少期望的序列号
                }
                
                // 交付消息给上层
                deliverToApplication(expectedSeq, data);
                buffer.remove(expectedSeq);
                expectedSeq++;
                delivered = true;
            }
            
            return delivered;
        }
        
        /**
         * 获取下一个期望的序列号（用于发送ACK）
         */
        int getExpectedSeq() {
            return expectedSeq;
        }
        
        /**
         * 清理缓存
         */
        void clear() {
            for (ByteBuf data : buffer.values()) {
                data.release();
            }
            buffer.clear();
        }
        
        /**
         * 获取缓存大小
         */
        int size() {
            return buffer.size();
        }
    }
    
    // 拥塞控制参数
    private static class CongestionControl {
        // 拥塞窗口相关
        int cwnd = 1;                     // 拥塞窗口大小（报文段数量）
        int ssthresh = Integer.MAX_VALUE; // 慢启动阈值
        
        // 丢包统计
        int totalPacketsSent = 0;
        int totalPacketsLost = 0;
        double lossRate = 0.0;
        
        // RTT统计
        long smoothedRTT = 1000L;         // 平滑RTT估计
        long rttVar = 0;                  // RTT方差
        long rto = 2000L;                 // 重传超时时间
        
        // 带宽估算
        long estimatedBandwidth = 0;      // 估算带宽（字节/秒）
        long lastUpdateTime = 0;
        long bytesSentSinceLastUpdate = 0;
        
        // 公平性控制
        double fairnessFactor = 1.0;      // 公平性调节因子
        
        /**
         * 更新丢包率
         */
        void updateLossRate(int packetsSent, int packetsLost) {
            this.totalPacketsSent += packetsSent;
            this.totalPacketsLost += packetsLost;
            
            if (totalPacketsSent > 0) {
                lossRate = (double) totalPacketsLost / totalPacketsSent;
            }
        }
        
        /**
         * 更新RTT估计
         */
        void updateRTTEstimate(long sampleRTT) {
            if (sampleRTT <= 0) {
                return;
            }
            
            if (smoothedRTT == 0) {
                smoothedRTT = sampleRTT;
                rttVar = sampleRTT / 2;
            } else {
                long alpha = 1; // 简化计算
                smoothedRTT = (alpha * sampleRTT + (8 - alpha) * smoothedRTT) / 8;
                long delta = Math.abs(sampleRTT - smoothedRTT);
                rttVar = (3 * rttVar + delta) / 4;
            }
            
            rto = Math.max(1000L, smoothedRTT + Math.max(1000L, 4 * rttVar));
        }
        
        /**
         * 更新带宽估算
         */
        void updateBandwidthEstimate(int bytesSent) {
            long currentTime = System.currentTimeMillis();
            bytesSentSinceLastUpdate += bytesSent;
            
            if (lastUpdateTime == 0) {
                lastUpdateTime = currentTime;
                return;
            }
            
            long elapsed = currentTime - lastUpdateTime;
            if (elapsed >= 1000) { // 至少1秒更新一次
                estimatedBandwidth = (bytesSentSinceLastUpdate * 1000) / elapsed;
                bytesSentSinceLastUpdate = 0;
                lastUpdateTime = currentTime;
            }
        }
        
        /**
         * 拥塞避免算法（TCP Reno风格）
         */
        void congestionAvoidance() {
            if (cwnd < ssthresh) {
                // 慢启动阶段：指数增长
                cwnd++;
            } else {
                // 拥塞避免阶段：线性增长
                cwnd += 1.0 / cwnd;
            }
            
            // 根据丢包率调整
            if (lossRate > 0.1) { // 丢包率超过10%
                ssthresh = Math.max(cwnd / 2, 2);
                cwnd = 1; // 快速重传/快速恢复
            }
            
            // 限制最大窗口大小
            cwnd = Math.min(cwnd, 64); // 最大64个报文段
        }
        
        /**
         * 获取当前发送窗口大小
         */
        int getSendWindow() {
            return cwnd;
        }
    }
    
    // 配置参数
    private static final int DEFAULT_WINDOW_SIZE = 32;          // 默认滑动窗口大小
    private static final int DEFAULT_MAX_RETRIES = 5;           // 默认最大重试次数
    private static final long DEFAULT_INITIAL_RTT = 1000L;      // 默认初始RTT（毫秒）
    private static final long DEFAULT_MAX_RTT = 30000L;         // 默认最大RTT（毫秒）
    private static final int MAX_BUFFER_SIZE = 1024;           // 最大接收缓冲区大小
    
    // 实例变量
    private final AtomicInteger nextSeq = new AtomicInteger(0);
    private final Map<Integer, MessageRecord> pendingMessages = new ConcurrentSkipListMap<>();
    private final Map<InetSocketAddress, ReceiveBuffer> receiveBuffers = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, CongestionControl> congestionControls = new ConcurrentHashMap<>();
    private final Map<Integer, Long> ackTimestamps = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final ReentrantLock windowLock = new ReentrantLock();
    
    // 滑动窗口控制
    private int windowSize = DEFAULT_WINDOW_SIZE;
    private int sendBase = 0;           // 发送窗口基序号
    private int nextSeqNum = 0;         // 下一个要发送的序号
    
    // RTT估算
    private long estimatedRTT = DEFAULT_INITIAL_RTT;
    private long devRTT = 0;
    private long timeoutInterval;
    
    // 统计信息
    private final AtomicInteger totalSent = new AtomicInteger(0);
    private final AtomicInteger totalAcked = new AtomicInteger(0);
    private final AtomicInteger totalRetransmitted = new AtomicInteger(0);
    private final AtomicInteger totalTimeouts = new AtomicInteger(0);
    private final AtomicInteger totalOutOfOrder = new AtomicInteger(0);
    private final AtomicInteger totalDuplicated = new AtomicInteger(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    
    /**
     * 构造函数
     */
    public UdpReliabilityManager() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "UdpReliabilityManager-Scheduler");
            t.setDaemon(true);
            return t;
        });
        updateTimeoutInterval();
    }
    
    /**
     * 发送消息（可靠模式）
//     * @param channel 发送通道
//     * @param remoteAddress 远程地址
     * @param message 消息包装器
     * @return 消息序列号，-1表示窗口已满
     */
    public int sendReliableMessage(P2PClientUdp client, P2PWrapper<?> message) {
        windowLock.lock();
        try {
            // 获取当前连接对应的拥塞控制状态
            CongestionControl cc = getCongestionControl(client.getRemote());
            
            // 检查发送窗口是否已满（考虑拥塞窗口）
            int effectiveWindow = Math.min(windowSize, cc.getSendWindow());
            if (nextSeqNum >= sendBase + effectiveWindow) {
                log.debug("Send window full: sendBase={}, nextSeqNum={}, effectiveWindow={}", 
                         sendBase, nextSeqNum, effectiveWindow);
                return -1; // 窗口已满
            }
            
            // 分配序列号
            int seq = nextSeqNum++;
            message.setSeq(seq);
            
            
            // 记录消息
            MessageRecord record = new MessageRecord(seq, message, client.getRemote());
            pendingMessages.put(seq, record);
            
            // 发送消息
            sendMessageInternal(client, message);
            totalSent.incrementAndGet();
            //totalBytesSent.addAndGet(message.readableBytes());
            
            // 更新拥塞控制统计
            cc.totalPacketsSent++;
            //cc.updateBandwidthEstimate(buffer.readableBytes());
            
            // 设置超时定时器
            scheduleTimeoutCheck(client,record);
            
            log.debug("Sent reliable message seq={}, command={}, cwnd={}", 
                     seq, message.getCommand(), cc.cwnd);
            return seq;
            
        } finally {
            windowLock.unlock();
        }
    }
    
    /**
     * 发送消息（不可靠模式）
     * @param channel 发送通道
     * @param remoteAddress 远程地址
     * @param message 消息包装器
     */
    public void sendUnreliableMessage(P2PClientUdp client, P2PWrapper<?> message) {
        sendMessageInternal(client, message);
    }
    
    /**
     * 处理接收到的ACK消息
     * @param ackSeq 确认的序列号
     * @param remoteAddress 远程地址
     */
    public void processAck(int ackSeq, InetSocketAddress remoteAddress) {
        windowLock.lock();
        try {
            MessageRecord record = pendingMessages.get(ackSeq);
            if (record != null && record.status == MessageStatus.SENT) {
                // 取消超时定时器
                if (record.timeoutFuture != null) {
                    record.timeoutFuture.cancel(false);
                }
                
                // 更新状态
                record.status = MessageStatus.ACKED;
                record.release();
                pendingMessages.remove(ackSeq);
                totalAcked.incrementAndGet();
                
                // 更新RTT估算
                long rtt = System.currentTimeMillis() - record.sendTime;
                updateRTTEstimate(rtt);
                
                // 更新拥塞控制状态
                CongestionControl cc = getCongestionControl(remoteAddress);
                cc.updateRTTEstimate(rtt);
                
                // 拥塞避免算法
                cc.congestionAvoidance();
                
                // 移动发送窗口
                moveSendWindow();
                
                log.debug("ACK received for seq={}, RTT={}ms, cwnd={}", 
                         ackSeq, rtt, cc.cwnd);
            } else if (record != null) {
                log.debug("ACK for seq={} with status={}", ackSeq, record.status);
            } else {
                log.debug("ACK for unknown seq={}", ackSeq);
            }
            
        } finally {
            windowLock.unlock();
        }
    }
    
    /**
     * 处理接收到的数据消息
     * @param seq 消息序列号
     * @param data 消息数据
     * @param remoteAddress 远程地址
     * @return 是否期望接收此消息（用于发送ACK）
     */
    public boolean processDataMessage(int seq, ByteBuf data, InetSocketAddress remoteAddress) {
        // 记录接收时间戳（用于计算RTT）
        ackTimestamps.put(seq, System.currentTimeMillis());
        
        // 获取或创建接收缓冲区
        ReceiveBuffer buffer = receiveBuffers.computeIfAbsent(remoteAddress, 
            k -> new ReceiveBuffer());
        
        // 检查接收缓冲区是否过大
        if (buffer.size() > MAX_BUFFER_SIZE) {
            log.warn("Receive buffer overflow for {}, size={}, clearing buffer", 
                    remoteAddress, buffer.size());
            buffer.clear();
        }
        
        // 添加到接收缓冲区
        boolean delivered = buffer.addMessage(seq, data);
        
        if (!delivered) {
            // 乱序到达的消息
            totalOutOfOrder.incrementAndGet();
            log.debug("Out-of-order message seq={} from {}, buffer size={}", 
                     seq, remoteAddress, buffer.size());
        }
        
        // 根据拥塞控制策略决定是否发送ACK
        // 这里简化处理：总是返回true表示需要ACK
        // 实际可以根据接收策略选择性地发送ACK
        
        return true;
    }
    
    /**
     * 发送ACK消息
     * @param channel 发送通道
     * @param remoteAddress 远程地址
     * @param seq 确认的序列号
     */
    public void sendAck(P2PClientUdp client, int seq) {
        P2PWrapper<Void> ackMessage = P2PWrapper.build(seq, P2PCommand.UDP_FRAME_ACK, null);
        sendUnreliableMessage(client, ackMessage);
        log.debug("Sent ACK for seq={} to {}", seq, client.getRemote());
    }
    
    /**
     * 发送累积ACK（确认到某个序列号的所有消息）
     * @param channel 发送通道
     * @param remoteAddress 远程地址
     */
    public void sendCumulativeAck(P2PClientUdp client) {
        ReceiveBuffer buffer = receiveBuffers.get(client.getRemote());
        if (buffer != null) {
            int expectedSeq = buffer.getExpectedSeq();
            if (expectedSeq > 0) {
                // 发送累积ACK，确认expectedSeq-1之前的所有消息
                sendAck(client, expectedSeq - 1);
                log.debug("Sent cumulative ACK up to seq={} to {}", expectedSeq - 1, client.getRemote());
            }
        }
    }
    
    /**
     * 获取拥塞控制状态
     */
    private CongestionControl getCongestionControl(InetSocketAddress remoteAddress) {
        return congestionControls.computeIfAbsent(remoteAddress, 
            k -> new CongestionControl());
    }
    
    /**
     * 清理指定远程地址的资源
     * @param remoteAddress 远程地址
     */
    public void cleanupForAddress(InetSocketAddress remoteAddress) {
        ReceiveBuffer buffer = receiveBuffers.remove(remoteAddress);
        if (buffer != null) {
            buffer.clear();
        }
        
        congestionControls.remove(remoteAddress);
        
        // 清理该地址相关的未确认消息
        pendingMessages.entrySet().removeIf(entry -> 
            entry.getValue().remoteAddress.equals(remoteAddress));
    }
    
    /**
     * 获取指定地址的拥塞窗口信息
     */
    public String getCongestionInfo(InetSocketAddress remoteAddress) {
        CongestionControl cc = congestionControls.get(remoteAddress);
        if (cc == null) {
            return "No congestion control data for " + remoteAddress;
        }
        
        return String.format(
            "Congestion Info: cwnd=%d, ssthresh=%d, lossRate=%.2f%%, RTT=%.2fms, Bandwidth=%.2fKB/s",
            cc.cwnd, cc.ssthresh, cc.lossRate * 100, cc.smoothedRTT / 1000000.0,
            cc.estimatedBandwidth / 1024.0
        );
    }
    
    /**
     * 清理资源
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
        
        // 释放所有未完成的消息
        for (MessageRecord record : pendingMessages.values()) {
            record.release();
        }
        pendingMessages.clear();
        
        // 清理所有接收缓冲区
        for (ReceiveBuffer buffer : receiveBuffers.values()) {
            buffer.clear();
        }
        receiveBuffers.clear();
        
        // 清理拥塞控制状态
        congestionControls.clear();
    }
    
    /**
     * 获取统计信息
     */
    public String getStatistics() {
        long avgRTT = estimatedRTT / 1000000L;
        long totalKB = totalBytesSent.get() / 1024;
        
        return String.format(
            "UdpReliabilityManager Stats:\n" +
            "  Transmission: Sent=%d, ACKed=%d, Retransmitted=%d, Timeouts=%d\n" +
            "  Sequencing: OutOfOrder=%d, Duplicated=%d, Pending=%d\n" +
            "  Window Control: WindowSize=%d, SendBase=%d, NextSeq=%d\n" +
            "  Performance: TotalBytes=%dKB, AvgRTT=%dms\n" +
            "  Active Connections: Buffers=%d, CC=%d",
            totalSent.get(), totalAcked.get(), totalRetransmitted.get(), totalTimeouts.get(),
            totalOutOfOrder.get(), totalDuplicated.get(), pendingMessages.size(),
            windowSize, sendBase, nextSeqNum,
            totalKB, avgRTT,
            receiveBuffers.size(), congestionControls.size()
        );
    }
    
    // ============== 私有方法 ==============
    
    private ByteBuf serializeMessage(P2PWrapper<?> message) {
        try {
            // TODO: 使用项目现有的序列化工具
            // 这里需要调用现有的SerializationUtil
            // 暂时返回null，实际实现需要集成现有序列化
            return SerializationUtil.serializeToByteBuf(message);
        } catch (Exception e) {
            log.error("Failed to serialize message: {}", e.getMessage(), e);
            return null;
        }
    }
    
    private void sendMessageInternal(P2PClientUdp client,P2PWrapper<?> message) {
        try {
            client.excute(message);
//            ChannelFuture future = channel.writeAndFlush(new DatagramPacket(buffer.retain(), client.getRemote()));
//            future.addListener(f -> {
//                if (!f.isSuccess()) {
//                    log.error("Failed to send message to {}: {}", client.getRemote(), f.cause().getMessage());
//                    // 更新拥塞控制：记录丢包
//                    CongestionControl cc = congestionControls.get(remoteAddress);
//                    if (cc != null) {
//                        cc.totalPacketsLost++;
//                    }
//                }
//            });
        } catch (Exception e) {
            log.error("Exception while sending message: {}", e.getMessage(), e);
        }
    }
    
    private void scheduleTimeoutCheck(P2PClientUdp client,MessageRecord record) {
        long timeout = Math.max(timeoutInterval, 1000L); // 至少1秒
        
        // 使用拥塞控制的RTO作为超时时间
        CongestionControl cc = congestionControls.get(record.remoteAddress);
        if (cc != null && cc.rto > 0) {
            timeout = Math.max(timeout, cc.rto);
        }
        
        record.timeoutFuture = scheduler.schedule(() -> {
            handleTimeout(client,record);
        }, timeout, TimeUnit.MILLISECONDS);
    }
    
    private void handleTimeout(P2PClientUdp client,MessageRecord record) {
        windowLock.lock();
        try {
            if (record.status != MessageStatus.SENT) {
                return; // 消息已经处理过了
            }
            
            record.retryCount++;
            if (record.retryCount > DEFAULT_MAX_RETRIES) {
                // 超过最大重试次数
                record.status = MessageStatus.TIMED_OUT;
                record.release();
                pendingMessages.remove(record.seq);
                totalTimeouts.incrementAndGet();
                
                // 更新拥塞控制：严重超时
                CongestionControl cc = congestionControls.get(record.remoteAddress);
                if (cc != null) {
                    cc.ssthresh = Math.max(cc.cwnd / 2, 2);
                    cc.cwnd = 1;
                }
                
                log.warn("Message seq={} timed out after {} retries to {}", 
                        record.seq, record.retryCount, record.remoteAddress);
            } else {
                // 重传消息
                record.status = MessageStatus.RETRANSMITTING;
                sendMessageInternal(client, record.message); // TODO: 需要channel
                totalRetransmitted.incrementAndGet();
                
                // 更新拥塞控制：丢包
                CongestionControl cc = congestionControls.get(record.remoteAddress);
                if (cc != null) {
                    cc.totalPacketsLost++;
                }
                
                // 重新设置超时定时器（使用指数退避）
                long backoffTimeout = (long) (timeoutInterval * Math.pow(2, record.retryCount));
                record.timeoutFuture = scheduler.schedule(() -> {
                    handleTimeout(client,record);
                }, backoffTimeout, TimeUnit.MILLISECONDS);
                
                log.debug("Retransmitting message seq={} to {}, retryCount={}", 
                         record.seq, record.remoteAddress, record.retryCount);
            }
            
        } finally {
            windowLock.unlock();
        }
    }
    
    private void updateRTTEstimate(long sampleRTT) {
        if (sampleRTT <= 0) {
            return;
        }
        
        // 使用TCP的RTT估计算法
        if (estimatedRTT == DEFAULT_INITIAL_RTT) {
            estimatedRTT = sampleRTT;
            devRTT = sampleRTT / 2;
        } else {
            estimatedRTT = (long) ((1 - 0.125) * estimatedRTT + 0.125 * sampleRTT);
            devRTT = (long) ((1 - 0.25) * devRTT + 0.25 * Math.abs(sampleRTT - estimatedRTT));
        }
        
        updateTimeoutInterval();
    }
    
    private void updateTimeoutInterval() {
        timeoutInterval = estimatedRTT + 4 * devRTT;
        timeoutInterval = Math.min(timeoutInterval, DEFAULT_MAX_RTT);
        timeoutInterval = Math.max(timeoutInterval, 1000L); // 至少1秒
    }
    
    private void moveSendWindow() {
        // 移动发送窗口到最小的未确认序列号
        while (!pendingMessages.isEmpty()) {
            Integer minSeq = ((ConcurrentSkipListMap<Integer, MessageRecord>) pendingMessages).firstKey();
            if (minSeq != null && minSeq == sendBase) {
                // 检查sendBase是否已确认
                MessageRecord record = pendingMessages.get(sendBase);
                if (record != null && record.status == MessageStatus.ACKED) {
                    pendingMessages.remove(sendBase);
                    sendBase++;
                } else {
                    break; // sendBase还未确认，不能移动窗口
                }
            } else {
                break;
            }
        }
    }
    
    // ============== 虚方法，需要子类或集成时实现 ==============
    
    /**
     * 交付消息给应用程序层
     * 这个方法需要在实际集成时实现
     */
    protected void deliverToApplication(int seq, ByteBuf data) {
        // 默认实现：释放缓冲区
        data.release();
        log.debug("Delivered message seq={} to application", seq);
    }
    
    // ============== 配置方法 ==============
    
    public void setWindowSize(int windowSize) {
        this.windowSize = Math.max(1, Math.min(windowSize, 256)); // 限制在1-256之间
    }
    
    public int getWindowSize() {
        return windowSize;
    }
    
    public long getEstimatedRTT() {
        return estimatedRTT;
    }
    
    public int getPendingCount() {
        return pendingMessages.size();
    }
    
    public int getReceiveBufferSize(InetSocketAddress remoteAddress) {
        ReceiveBuffer buffer = receiveBuffers.get(remoteAddress);
        return buffer != null ? buffer.size() : 0;
    }
}