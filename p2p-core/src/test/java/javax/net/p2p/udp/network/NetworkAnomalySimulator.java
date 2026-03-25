package javax.net.p2p.udp.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * 网络异常模拟器
 * 
 * 用于模拟各种网络异常情况，包括：
 * 1. 数据包丢失
 * 2. 传输延迟
 * 3. 数据包重复
 * 4. 乱序传输
 * 5. 带宽限制
 * 6. 网络抖动
 * 
 * 使用场景：
 * - 测试UDP可靠性机制的健壮性
 * - 验证重传机制的正确性
 * - 测试流量控制和拥塞控制的适应性
 * 
 * @author karl
 */
@Slf4j
public class NetworkAnomalySimulator {
    
    /**
     * 网络异常类型
     */
    public enum AnomalyType {
        /** 丢包 - 模拟网络丢包 */
        PACKET_LOSS,
        /** 延迟 - 模拟网络延迟 */
        DELAY,
        /** 重复 - 模拟数据包重复 */
        DUPLICATE,
        /** 乱序 - 模拟数据包乱序 */
        OUT_OF_ORDER,
        /** 抖动 - 模拟网络抖动（延迟变化） */
        JITTER,
        /** 带宽限制 - 模拟带宽限制 */
        BANDWIDTH_LIMIT,
        /** 组合异常 - 多种异常同时发生 */
        COMBINED
    }
    
    /**
     * 网络异常配置
     */
    public static class NetworkConfig {
        /** 丢包率 (0.0 - 1.0) */
        private double packetLossRate = 0.0;
        /** 平均延迟 (毫秒) */
        private int averageDelayMs = 0;
        /** 延迟标准差 (毫秒) */
        private int delayStdDevMs = 0;
        /** 重复包率 (0.0 - 1.0) */
        private double duplicateRate = 0.0;
        /** 乱序概率 (0.0 - 1.0) */
        private double outOfOrderProbability = 0.0;
        /** 最大乱序偏移 (数据包数量) */
        private int maxOutOfOrderOffset = 5;
        /** 带宽限制 (字节/秒) */
        private int bandwidthLimitBps = 0;
        /** 是否启用抖动模拟 */
        private boolean jitterEnabled = false;
        /** 抖动范围 (毫秒) */
        private int jitterRangeMs = 50;
        
        public NetworkConfig() {}
        
        public NetworkConfig(double packetLossRate, int averageDelayMs) {
            this.packetLossRate = packetLossRate;
            this.averageDelayMs = averageDelayMs;
        }
        
        // Getter和Setter方法
        public double getPacketLossRate() { return packetLossRate; }
        public void setPacketLossRate(double packetLossRate) { this.packetLossRate = packetLossRate; }
        
        public int getAverageDelayMs() { return averageDelayMs; }
        public void setAverageDelayMs(int averageDelayMs) { this.averageDelayMs = averageDelayMs; }
        
        public int getDelayStdDevMs() { return delayStdDevMs; }
        public void setDelayStdDevMs(int delayStdDevMs) { this.delayStdDevMs = delayStdDevMs; }
        
        public double getDuplicateRate() { return duplicateRate; }
        public void setDuplicateRate(double duplicateRate) { this.duplicateRate = duplicateRate; }
        
        public double getOutOfOrderProbability() { return outOfOrderProbability; }
        public void setOutOfOrderProbability(double outOfOrderProbability) { this.outOfOrderProbability = outOfOrderProbability; }
        
        public int getMaxOutOfOrderOffset() { return maxOutOfOrderOffset; }
        public void setMaxOutOfOrderOffset(int maxOutOfOrderOffset) { this.maxOutOfOrderOffset = maxOutOfOrderOffset; }
        
        public int getBandwidthLimitBps() { return bandwidthLimitBps; }
        public void setBandwidthLimitBps(int bandwidthLimitBps) { this.bandwidthLimitBps = bandwidthLimitBps; }
        
        public boolean isJitterEnabled() { return jitterEnabled; }
        public void setJitterEnabled(boolean jitterEnabled) { this.jitterEnabled = jitterEnabled; }
        
        public int getJitterRangeMs() { return jitterRangeMs; }
        public void setJitterRangeMs(int jitterRangeMs) { this.jitterRangeMs = jitterRangeMs; }
        
        @Override
        public String toString() {
            return String.format("NetworkConfig{loss=%.2f%%, delay=%dms, duplicate=%.2f%%, outOfOrder=%.2f%%, bandwidth=%d bps}",
                packetLossRate * 100, averageDelayMs, duplicateRate * 100, outOfOrderProbability * 100, bandwidthLimitBps);
        }
    }
    
    /**
     * 预定义网络场景
     */
    public static class NetworkScenarios {
        /** 理想网络 - 无异常 */
        public static final NetworkConfig IDEAL = new NetworkConfig();
        
        /** 良好网络 - 轻微延迟，低丢包率 */
        public static final NetworkConfig GOOD = new NetworkConfig(0.01, 20);
        
        /** 一般网络 - 中等延迟，中等丢包率 */
        public static final NetworkConfig AVERAGE = new NetworkConfig(0.05, 100);
        
        /** 较差网络 - 高延迟，高丢包率 */
        public static final NetworkConfig POOR = new NetworkConfig(0.15, 300);
        
        /** 恶劣网络 - 极高丢包率和延迟 */
        public static final NetworkConfig TERRIBLE = new NetworkConfig(0.30, 500);
        
        /** 移动网络 - 模拟4G/5G移动网络特性 */
        public static final NetworkConfig MOBILE = new NetworkConfig(0.10, 150);
        
        /** 卫星网络 - 高延迟，低丢包率 */
        public static final NetworkConfig SATELLITE = new NetworkConfig(0.02, 600);
        
        /** 无线网络 - 中等丢包率，有抖动 */
        public static final NetworkConfig WIFI = new NetworkConfig(0.08, 50);
    }
    
    private final Random random = new Random();
    private final NetworkConfig config;
    private final AtomicInteger packetCounter = new AtomicInteger(0);
    private final Map<Integer, Long> packetTimestamps = new ConcurrentHashMap<>();
    private final Map<Integer, DatagramPacket> pendingPackets = new ConcurrentHashMap<>();
    private final List<PacketListener> listeners = new ArrayList<>();
    
    // 带宽控制相关
    private long lastBandwidthCheckTime = System.currentTimeMillis();
    private int bytesSentInWindow = 0;
    private final int BANDWIDTH_WINDOW_MS = 1000;
    
    public NetworkAnomalySimulator() {
        this.config = NetworkScenarios.IDEAL;
    }
    
    public NetworkAnomalySimulator(NetworkConfig config) {
        this.config = config;
    }
    
    /**
     * 模拟发送数据包
     */
    public DatagramPacket simulateSend(DatagramPacket packet, ChannelHandlerContext ctx) {
        int packetId = packetCounter.incrementAndGet();
        packetTimestamps.put(packetId, System.currentTimeMillis());
        
        // 检查丢包
        if (shouldDropPacket()) {
            log.debug("Packet {} dropped (loss rate: {}%)", packetId, config.getPacketLossRate() * 100);
            notifyPacketDropped(packetId, packet);
            return null;
        }
        
        // 复制数据包（防止修改原始数据）
        ByteBuf originalContent = packet.content();
        ByteBuf copiedContent = originalContent.copy();
        DatagramPacket simulatedPacket = new DatagramPacket(copiedContent, packet.recipient());
        
        // 检查重复包
        if (shouldDuplicatePacket()) {
            log.debug("Packet {} duplicated (duplicate rate: {}%)", packetId, config.getDuplicateRate() * 100);
            scheduleDuplicate(simulatedPacket, ctx);
        }
        
        // 检查乱序
        if (shouldReorderPacket()) {
            log.debug("Packet {} will be reordered", packetId);
            scheduleOutOfOrder(simulatedPacket, packetId, ctx);
            return null; // 乱序包会延迟发送
        }
        
        // 应用延迟
        int delay = calculateDelay();
        if (delay > 0) {
            scheduleDelayedSend(simulatedPacket, delay, ctx);
            return null; // 延迟包会稍后发送
        }
        
        // 检查带宽限制
        if (config.getBandwidthLimitBps() > 0) {
            if (!checkBandwidthLimit(simulatedPacket.content().readableBytes())) {
                log.debug("Packet {} delayed due to bandwidth limit", packetId);
                scheduleBandwidthDelayed(simulatedPacket, ctx);
                return null;
            }
        }
        
        // 立即发送
        notifyPacketSent(packetId, simulatedPacket);
        return simulatedPacket;
    }
    
    /**
     * 检查是否应该丢包
     */
    private boolean shouldDropPacket() {
        return random.nextDouble() < config.getPacketLossRate();
    }
    
    /**
     * 检查是否应该产生重复包
     */
    private boolean shouldDuplicatePacket() {
        return random.nextDouble() < config.getDuplicateRate();
    }
    
    /**
     * 检查是否应该乱序
     */
    private boolean shouldReorderPacket() {
        return random.nextDouble() < config.getOutOfOrderProbability();
    }
    
    /**
     * 计算延迟（考虑平均值、标准差和抖动）
     */
    private int calculateDelay() {
        int delay = config.getAverageDelayMs();
        
        // 添加标准差影响（正态分布）
        if (config.getDelayStdDevMs() > 0) {
            delay += (int) (random.nextGaussian() * config.getDelayStdDevMs());
            delay = Math.max(0, delay); // 确保非负
        }
        
        // 添加抖动
        if (config.isJitterEnabled()) {
            int jitter = random.nextInt(config.getJitterRangeMs() * 2) - config.getJitterRangeMs();
            delay += jitter;
            delay = Math.max(0, delay); // 确保非负
        }
        
        return delay;
    }
    
    /**
     * 检查带宽限制
     */
    private boolean checkBandwidthLimit(int packetSize) {
        long currentTime = System.currentTimeMillis();
        
        // 检查是否进入新的时间窗口
        if (currentTime - lastBandwidthCheckTime > BANDWIDTH_WINDOW_MS) {
            bytesSentInWindow = 0;
            lastBandwidthCheckTime = currentTime;
        }
        
        // 检查是否超过带宽限制
        int maxBytesPerWindow = config.getBandwidthLimitBps() * BANDWIDTH_WINDOW_MS / 1000;
        if (bytesSentInWindow + packetSize > maxBytesPerWindow) {
            return false;
        }
        
        bytesSentInWindow += packetSize;
        return true;
    }
    
    /**
     * 安排延迟发送
     */
    private void scheduleDelayedSend(DatagramPacket packet, int delayMs, ChannelHandlerContext ctx) {
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                ctx.writeAndFlush(packet);
                notifyPacketDelayed(packetCounter.get(), packet, delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Delay thread interrupted", e);
            }
        }).start();
    }
    
    /**
     * 安排重复包发送
     */
    private void scheduleDuplicate(DatagramPacket packet, ChannelHandlerContext ctx) {
        new Thread(() -> {
            try {
                // 随机延迟后发送重复包
                Thread.sleep(50 + random.nextInt(100));
                
                // 创建重复包
                ByteBuf duplicatedContent = packet.content().copy();
                DatagramPacket duplicatedPacket = new DatagramPacket(duplicatedContent, packet.recipient());
                
                ctx.writeAndFlush(duplicatedPacket);
                notifyPacketDuplicated(packetCounter.get(), duplicatedPacket);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Duplicate thread interrupted", e);
            }
        }).start();
    }
    
    /**
     * 安排乱序发送
     */
    private void scheduleOutOfOrder(DatagramPacket packet, int packetId, ChannelHandlerContext ctx) {
        pendingPackets.put(packetId, packet);
        
        // 随机决定乱序偏移
        int offset = 1 + random.nextInt(config.getMaxOutOfOrderOffset());
        
        new Thread(() -> {
            try {
                // 等待其他包先发送
                Thread.sleep(offset * 10L); // 每个偏移10ms
                
                pendingPackets.remove(packetId);
                ctx.writeAndFlush(packet);
                notifyPacketReordered(packetId, packet, offset);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Out-of-order thread interrupted", e);
            }
        }).start();
    }
    
    /**
     * 安排带宽限制延迟
     */
    private void scheduleBandwidthDelayed(DatagramPacket packet, ChannelHandlerContext ctx) {
        new Thread(() -> {
            try {
                // 计算需要等待的时间
                long currentTime = System.currentTimeMillis();
                long timeUntilNextWindow = BANDWIDTH_WINDOW_MS - (currentTime - lastBandwidthCheckTime);
                if (timeUntilNextWindow > 0) {
                    Thread.sleep(timeUntilNextWindow);
                }
                
                // 重置带宽计数器并发送
                bytesSentInWindow = packet.content().readableBytes();
                lastBandwidthCheckTime = System.currentTimeMillis();
                
                ctx.writeAndFlush(packet);
                notifyBandwidthLimited(packetCounter.get(), packet);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Bandwidth delay thread interrupted", e);
            }
        }).start();
    }
    
    /**
     * 添加数据包监听器
     */
    public void addPacketListener(PacketListener listener) {
        listeners.add(listener);
    }
    
    /**
     * 移除数据包监听器
     */
    public void removePacketListener(PacketListener listener) {
        listeners.remove(listener);
    }
    
    // 通知方法
    private void notifyPacketSent(int packetId, DatagramPacket packet) {
        for (PacketListener listener : listeners) {
            listener.onPacketSent(packetId, packet);
        }
    }
    
    private void notifyPacketDropped(int packetId, DatagramPacket packet) {
        for (PacketListener listener : listeners) {
            listener.onPacketDropped(packetId, packet);
        }
    }
    
    private void notifyPacketDelayed(int packetId, DatagramPacket packet, int delayMs) {
        for (PacketListener listener : listeners) {
            listener.onPacketDelayed(packetId, packet, delayMs);
        }
    }
    
    private void notifyPacketDuplicated(int packetId, DatagramPacket packet) {
        for (PacketListener listener : listeners) {
            listener.onPacketDuplicated(packetId, packet);
        }
    }
    
    private void notifyPacketReordered(int packetId, DatagramPacket packet, int offset) {
        for (PacketListener listener : listeners) {
            listener.onPacketReordered(packetId, packet, offset);
        }
    }
    
    private void notifyBandwidthLimited(int packetId, DatagramPacket packet) {
        for (PacketListener listener : listeners) {
            listener.onBandwidthLimited(packetId, packet);
        }
    }
    
    /**
     * 获取统计信息
     */
    public NetworkStatistics getStatistics() {
        NetworkStatistics stats = new NetworkStatistics();
        stats.setTotalPackets(packetCounter.get());
        stats.setConfig(config);
        return stats;
    }
    
    /**
     * 数据包监听器接口
     */
    public interface PacketListener {
        void onPacketSent(int packetId, DatagramPacket packet);
        void onPacketDropped(int packetId, DatagramPacket packet);
        void onPacketDelayed(int packetId, DatagramPacket packet, int delayMs);
        void onPacketDuplicated(int packetId, DatagramPacket packet);
        void onPacketReordered(int packetId, DatagramPacket packet, int offset);
        void onBandwidthLimited(int packetId, DatagramPacket packet);
    }
    
    /**
     * 网络统计信息
     */
    public static class NetworkStatistics {
        private int totalPackets;
        private int droppedPackets;
        private int delayedPackets;
        private int duplicatedPackets;
        private int reorderedPackets;
        private int bandwidthLimitedPackets;
        private NetworkConfig config;
        
        // Getter和Setter方法
        public int getTotalPackets() { return totalPackets; }
        public void setTotalPackets(int totalPackets) { this.totalPackets = totalPackets; }
        
        public int getDroppedPackets() { return droppedPackets; }
        public void setDroppedPackets(int droppedPackets) { this.droppedPackets = droppedPackets; }
        
        public int getDelayedPackets() { return delayedPackets; }
        public void setDelayedPackets(int delayedPackets) { this.delayedPackets = delayedPackets; }
        
        public int getDuplicatedPackets() { return duplicatedPackets; }
        public void setDuplicatedPackets(int duplicatedPackets) { this.duplicatedPackets = duplicatedPackets; }
        
        public int getReorderedPackets() { return reorderedPackets; }
        public void setReorderedPackets(int reorderedPackets) { this.reorderedPackets = reorderedPackets; }
        
        public int getBandwidthLimitedPackets() { return bandwidthLimitedPackets; }
        public void setBandwidthLimitedPackets(int bandwidthLimitedPackets) { this.bandwidthLimitedPackets = bandwidthLimitedPackets; }
        
        public NetworkConfig getConfig() { return config; }
        public void setConfig(NetworkConfig config) { this.config = config; }
        
        @Override
        public String toString() {
            return String.format("NetworkStatistics{total=%d, dropped=%d (%.1f%%), delayed=%d, duplicated=%d, reordered=%d, bandwidthLimited=%d}",
                totalPackets, droppedPackets, 
                totalPackets > 0 ? (droppedPackets * 100.0 / totalPackets) : 0.0,
                delayedPackets, duplicatedPackets, reorderedPackets, bandwidthLimitedPackets);
        }
    }
}