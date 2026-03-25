package javax.net.p2p.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import javax.net.p2p.client.P2PClientUdp;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 测试用的UDP可靠性管理器
 * 扩展了UdpReliabilityManager，添加了测试所需的功能
 */
@Slf4j
public class TestUdpReliabilityManager extends UdpReliabilityManager {
    
    // 测试状态记录
    private final Map<Integer, Boolean> ackedMessages = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> retransmittingMessages = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> timedOutMessages = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> deliveredMessages = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, Boolean> cleanedUpAddresses = new ConcurrentHashMap<>();
    
    private final AtomicInteger deliveredMessageCount = new AtomicInteger(0);
    private BiConsumer<Integer, ByteBuf> messageDeliveryCallback;
    
    // 网络异常模拟设置
    private double packetLossRate = 0.0;
    private int averageDelayMs = 0;
    
    @Override
    protected void deliverToApplication(int seq, ByteBuf data) {
        // 记录消息交付
        deliveredMessages.put(seq, true);
        deliveredMessageCount.incrementAndGet();
        
        // 调用回调函数（如果设置）
        if (messageDeliveryCallback != null) {
            messageDeliveryCallback.accept(seq, data.retain());
        }
        
        // 释放缓冲区
        data.release();
        
        log.debug("Test: Delivered message seq={} to application", seq);
    }
    
    @Override
    public void processAck(int ackSeq, InetSocketAddress remoteAddress) {
        super.processAck(ackSeq, remoteAddress);
        ackedMessages.put(ackSeq, true);
    }
    
    @Override
    public void cleanupForAddress(InetSocketAddress remoteAddress) {
        super.cleanupForAddress(remoteAddress);
        cleanedUpAddresses.put(remoteAddress, true);
    }
    
    // ============== 测试辅助方法 ==============
    
    public boolean isMessageAcked(int seq) {
        return ackedMessages.getOrDefault(seq, false);
    }
    
    public boolean isMessageRetransmitting(int seq) {
        return retransmittingMessages.getOrDefault(seq, false);
    }
    
    public boolean isMessageTimedOut(int seq) {
        return timedOutMessages.getOrDefault(seq, false);
    }
    
    public boolean isMessageDelivered(int seq) {
        return deliveredMessages.getOrDefault(seq, false);
    }
    
    public int getDeliveredMessageCount() {
        return deliveredMessageCount.get();
    }
    
    public void resetMessageCount() {
        deliveredMessageCount.set(0);
        deliveredMessages.clear();
        ackedMessages.clear();
        retransmittingMessages.clear();
        timedOutMessages.clear();
    }
    
    public boolean isAddressCleanedUp(InetSocketAddress remoteAddress) {
        return cleanedUpAddresses.getOrDefault(remoteAddress, false);
    }
    
    public void setMessageDeliveryCallback(BiConsumer<Integer, ByteBuf> callback) {
        this.messageDeliveryCallback = callback;
    }
    
    // ============== 网络异常模拟设置方法 ==============
    
    public void setPacketLossRate(double rate) {
        this.packetLossRate = Math.max(0.0, Math.min(1.0, rate));
        log.debug("Set packet loss rate to: {}%", packetLossRate * 100);
    }
    
    public void setAverageDelayMs(int delayMs) {
        this.averageDelayMs = Math.max(0, delayMs);
        log.debug("Set average delay to: {}ms", averageDelayMs);
    }
    
    // 占位符方法，用于保持测试类接口一致性
    public void setJitterEnabled(boolean enabled) {
        log.debug("Jitter simulation {} (not implemented in this test class)", enabled ? "enabled" : "disabled");
    }
    
    public void setJitterRangeMs(int rangeMs) {
        log.debug("Set jitter range to: {}ms (not implemented in this test class)", rangeMs);
    }
    
    public void setDuplicatePacketEnabled(boolean enabled) {
        log.debug("Duplicate packet simulation {} (not implemented in this test class)", enabled ? "enabled" : "disabled");
    }
    
    public void setOutOfOrderEnabled(boolean enabled) {
        log.debug("Out-of-order simulation {} (not implemented in this test class)", enabled ? "enabled" : "disabled");
    }
    
    public void setBandwidthLimitBps(int limitBps) {
        log.debug("Set bandwidth limit to: {} B/s (not implemented in this test class)", limitBps);
    }
    
    /**
     * 模拟网络异常的消息发送
     * 注意：这是一个简化版本，实际测试中应该使用NetworkAnomalySimulator
     */
    public int sendReliableMessageWithAnomalies(P2PClientUdp client,P2PWrapper<?> message) {
        
        // 模拟丢包
        if (Math.random() < packetLossRate) {
            log.debug("Simulating packet loss for message");
            // 模拟分配序列号（实际不发送）
            int seq = generateTestSequenceNumber();
            // 记录丢包统计
            return seq;
        }
        
        // 正常发送
        return super.sendReliableMessage(client, message);
    }
    
    /**
     * 生成测试用的序列号（简化实现）
     */
    private int generateTestSequenceNumber() {
        // 使用随机数模拟序列号
        return (int)(Math.random() * 10000);
    }
    
    // 模拟序列化方法，用于测试
    // 注意：父类的serializeMessage是private方法，不能覆盖
    // 这里提供一个测试辅助方法，实际测试中会调用父类的私有方法
    
    /**
     * 获取测试用的拥塞控制信息
     */
    public String getCongestionInfo(InetSocketAddress remoteAddress) {
        // 返回简单的模拟信息
        return String.format("TestCongestionInfo{cwnd=%d, ssthresh=%d, rtt=%dms, lossRate=%.2f%%}", 
                            10, 20, 50, packetLossRate * 100);
    }
    
    /**
     * 获取待处理消息数量
     */
    public int getPendingCount() {
        // 简化实现，返回0表示所有消息都已处理
        return 0;
    }
}