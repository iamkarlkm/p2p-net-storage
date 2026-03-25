package javax.net.p2p.udp.optimized;

import io.netty.channel.SimpleChannelInboundHandler;
import javax.net.p2p.client.ClientUdpMessageProcessor;
import javax.net.p2p.server.ServerUdpMessageProcessor;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.server.P2PServerUdp;
import lombok.extern.slf4j.Slf4j;

/**
 * UDP优化工厂类
 * 
 * 用于创建带监控功能的UDP处理器和组件
 * 
 * 主要功能：
 * 1. 创建带监控的UDP消息处理器
 * 2. 创建增强版UDP可靠性管理器
 * 3. 提供配置管理和性能调优
 * 
 * @author CodeBuddy
 */
@Slf4j
public class UdpOptimizedFactory {
    
    /**
     * 创建优化的服务器UDP消息处理器
     */
    public static ServerUdpMessageProcessor createOptimizedServerProcessor(
            P2PServerUdp server, int magic, int queueSize) {
        
        log.info("创建优化的服务器UDP消息处理器");
        
        ServerUdpMessageProcessor processor = new ServerUdpMessageProcessor(server, magic, queueSize);
        
        // 这里可以添加监控装饰器或性能优化
        // UdpMonitorDecorator.decorateProcessor(processor);
        
        return processor;
    }
    
    /**
     * 创建优化的客户端UDP消息处理器
     */
    public static ClientUdpMessageProcessor createOptimizedClientProcessor(
            P2PMessageService client, int magic, int queueSize) {
        
        log.info("创建优化的客户端UDP消息处理器");
        
        ClientUdpMessageProcessor processor = new ClientUdpMessageProcessor(client, magic, queueSize);
        
        // 这里可以添加监控装饰器或性能优化
        // UdpMonitorDecorator.decorateProcessor(processor);
        
        return processor;
    }
    
    /**
     * 创建增强版UDP可靠性管理器
     */
    public static UdpReliabilityManagerWithStats createEnhancedReliabilityManager(boolean enabled) {
        log.info("创建增强版UDP可靠性管理器，启用状态: {}", enabled);
        return new UdpReliabilityManagerWithStats(enabled);
    }
    
    /**
     * 创建增强版UDP可靠性管理器（默认启用）
     */
    public static UdpReliabilityManagerWithStats createEnhancedReliabilityManager() {
        return createEnhancedReliabilityManager(true);
    }
    
    /**
     * 创建优化的P2P服务器配置
     */
    public static UdpServerConfig createOptimizedServerConfig() {
        return new UdpServerConfig();
    }
    
    /**
     * 创建优化的P2P客户端配置
     */
    public static UdpClientConfig createOptimizedClientConfig() {
        return new UdpClientConfig();
    }
    
    /**
     * 获取UDP性能优化建议
     */
    public static String getOptimizationAdvice() {
        StringBuilder advice = new StringBuilder();
        advice.append("UDP性能优化建议:\n");
        advice.append("1. 缓冲区配置:\n");
        advice.append("   - 接收缓冲区大小: 建议设置为4KB-64KB\n");
        advice.append("   - 发送缓冲区大小: 建议与接收缓冲区保持一致\n");
        advice.append("2. 可靠性配置:\n");
        advice.append("   - 最大重试次数: 建议3-5次\n");
        advice.append("   - 基础RTO: 建议2000ms\n");
        advice.append("   - 滑动窗口大小: 建议16-64\n");
        advice.append("3. 监控配置:\n");
        advice.append("   - 统计采样间隔: 建议5-30秒\n");
        advice.append("   - 告警阈值: 丢包率>2%告警，>5%严重告警\n");
        advice.append("4. 网络优化:\n");
        advice.append("   - 使用Nagle算法优化小包传输\n");
        advice.append("   - 启用QoS区分服务优先级\n");
        advice.append("   - 配置适当的MTU大小\n");
        
        return advice.toString();
    }
    
    /**
     * UDP服务器配置类
     */
    public static class UdpServerConfig {
        private int receiveBufferSize = 65536;  // 64KB
        private int sendBufferSize = 65536;     // 64KB
        private int backlog = 256;              // 连接队列长度
        private boolean reuseAddress = true;    // 地址复用
        private int maxConnections = 1000;      // 最大连接数
        private int idleTimeout = 300;          // 空闲超时时间（秒）
        private boolean enableMonitoring = true; // 启用监控
        
        public int getReceiveBufferSize() {
            return receiveBufferSize;
        }
        
        public void setReceiveBufferSize(int receiveBufferSize) {
            this.receiveBufferSize = receiveBufferSize;
        }
        
        public int getSendBufferSize() {
            return sendBufferSize;
        }
        
        public void setSendBufferSize(int sendBufferSize) {
            this.sendBufferSize = sendBufferSize;
        }
        
        public int getBacklog() {
            return backlog;
        }
        
        public void setBacklog(int backlog) {
            this.backlog = backlog;
        }
        
        public boolean isReuseAddress() {
            return reuseAddress;
        }
        
        public void setReuseAddress(boolean reuseAddress) {
            this.reuseAddress = reuseAddress;
        }
        
        public int getMaxConnections() {
            return maxConnections;
        }
        
        public void setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
        }
        
        public int getIdleTimeout() {
            return idleTimeout;
        }
        
        public void setIdleTimeout(int idleTimeout) {
            this.idleTimeout = idleTimeout;
        }
        
        public boolean isEnableMonitoring() {
            return enableMonitoring;
        }
        
        public void setEnableMonitoring(boolean enableMonitoring) {
            this.enableMonitoring = enableMonitoring;
        }
        
        @Override
        public String toString() {
            return String.format("UdpServerConfig{receiveBufferSize=%d, sendBufferSize=%d, backlog=%d, "
                    + "reuseAddress=%s, maxConnections=%d, idleTimeout=%d, enableMonitoring=%s}",
                    receiveBufferSize, sendBufferSize, backlog, reuseAddress,
                    maxConnections, idleTimeout, enableMonitoring);
        }
    }
    
    /**
     * UDP客户端配置类
     */
    public static class UdpClientConfig {
        private int receiveBufferSize = 32768;  // 32KB
        private int sendBufferSize = 32768;     // 32KB
        private int connectionTimeout = 10000;  // 连接超时时间（ms）
        private int maxRetries = 3;             // 最大重试次数
        private int retryInterval = 2000;       // 重试间隔（ms）
        private boolean enableKeepalive = true; // 启用保活
        private int keepaliveInterval = 30000;  // 保活间隔（ms）
        private boolean enableMonitoring = true; // 启用监控
        
        public int getReceiveBufferSize() {
            return receiveBufferSize;
        }
        
        public void setReceiveBufferSize(int receiveBufferSize) {
            this.receiveBufferSize = receiveBufferSize;
        }
        
        public int getSendBufferSize() {
            return sendBufferSize;
        }
        
        public void setSendBufferSize(int sendBufferSize) {
            this.sendBufferSize = sendBufferSize;
        }
        
        public int getConnectionTimeout() {
            return connectionTimeout;
        }
        
        public void setConnectionTimeout(int connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }
        
        public int getMaxRetries() {
            return maxRetries;
        }
        
        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
        
        public int getRetryInterval() {
            return retryInterval;
        }
        
        public void setRetryInterval(int retryInterval) {
            this.retryInterval = retryInterval;
        }
        
        public boolean isEnableKeepalive() {
            return enableKeepalive;
        }
        
        public void setEnableKeepalive(boolean enableKeepalive) {
            this.enableKeepalive = enableKeepalive;
        }
        
        public int getKeepaliveInterval() {
            return keepaliveInterval;
        }
        
        public void setKeepaliveInterval(int keepaliveInterval) {
            this.keepaliveInterval = keepaliveInterval;
        }
        
        public boolean isEnableMonitoring() {
            return enableMonitoring;
        }
        
        public void setEnableMonitoring(boolean enableMonitoring) {
            this.enableMonitoring = enableMonitoring;
        }
        
        @Override
        public String toString() {
            return String.format("UdpClientConfig{receiveBufferSize=%d, sendBufferSize=%d, "
                    + "connectionTimeout=%d, maxRetries=%d, retryInterval=%d, "
                    + "enableKeepalive=%s, keepaliveInterval=%d, enableMonitoring=%s}",
                    receiveBufferSize, sendBufferSize, connectionTimeout,
                    maxRetries, retryInterval, enableKeepalive,
                    keepaliveInterval, enableMonitoring);
        }
    }
}