package javax.net.p2p.config;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

/**
 * TCP协议优化配置类
 * 提供TCP协议层的性能优化和稳定性配置
 * 
 * 主要优化点：
 * 1. 缓冲区优化：根据网络条件和数据块大小调整缓冲区
 * 2. 连接参数优化：优化连接建立和保持参数
 * 3. 流量控制：实现高效的流量控制机制
 * 4. 连接复用：优化连接复用策略
 * 
 * 性能目标：
 * - 高吞吐量：通过合理的缓冲区设置
 * - 低延迟：通过TCP_NODELAY和快速重传
 * - 高稳定性：通过心跳和连接监控
 * - 资源效率：通过连接池和对象复用
 * 
 * @version 1.0
 * @since 2026-03-13
 */
@Slf4j
public class TcpOptimizationConfig {
    
    // TCP缓冲区配置常量
    public static final int TCP_RCV_BUFFER_SIZE = 1024 * 1024;      // 1MB接收缓冲区
    public static final int TCP_SND_BUFFER_SIZE = 1024 * 1024;      // 1MB发送缓冲区
    public static final int TCP_BACKLOG_SIZE = 1024;               // 连接队列大小
    public static final int TCP_KEEPIDLE = 30;                     // 30秒后开始心跳
    public static final int TCP_KEEPINTERVAL = 10;                 // 每10秒发送一次心跳
    public static final int TCP_KEEPCOUNT = 5;                     // 最多尝试5次
    
    // 连接超时配置
    public static final int CONNECT_TIMEOUT_MS = 5000;             // 5秒连接超时
    public static final int SOCKET_TIMEOUT_MS = 30000;             // 30秒Socket超时
    public static final int IDLE_TIMEOUT_SECONDS = 60;             // 60秒空闲超时
    
    // 流量控制配置
    public static final int WRITE_BUFFER_WATER_MARK_LOW = 32 * 1024;   // 32KB低水位
    public static final int WRITE_BUFFER_WATER_MARK_HIGH = 64 * 1024;  // 64KB高水位
    
    /**
     * 初始化服务器端TCP优化配置
     * 为服务器端Socket和子Socket设置优化的TCP参数
     * 
     * @param bootstrap 服务器引导类
     */
    public static void initServerOptimizedOptions(ServerBootstrap bootstrap) {
        if (bootstrap == null) {
            log.warn("ServerBootstrap is null, cannot set TCP options");
            return;
        }
        
        // 服务器Socket选项
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                 .option(ChannelOption.SO_BACKLOG, TCP_BACKLOG_SIZE)
                 .option(ChannelOption.SO_REUSEADDR, true)  // 地址复用，便于快速重启
                 .option(ChannelOption.TCP_NODELAY, true)   // 禁用Nagle算法，减少延迟
                 .option(ChannelOption.SO_KEEPALIVE, true); // 启用KeepAlive
        
        // 子Socket选项（客户端连接）
        bootstrap.childOption(ChannelOption.TCP_NODELAY, true)
                 .childOption(ChannelOption.SO_KEEPALIVE, true)
                 .childOption(ChannelOption.SO_RCVBUF, TCP_RCV_BUFFER_SIZE)
                 .childOption(ChannelOption.SO_SNDBUF, TCP_SND_BUFFER_SIZE)
                 .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, 
                     new io.netty.channel.WriteBufferWaterMark(
                         WRITE_BUFFER_WATER_MARK_LOW, WRITE_BUFFER_WATER_MARK_HIGH))
                 .childOption(ChannelOption.ALLOCATOR, io.netty.buffer.PooledByteBufAllocator.DEFAULT);
        
        log.debug("Server TCP optimized options configured: RCV_BUF={}KB, SND_BUF={}KB, BACKLOG={}", 
                 TCP_RCV_BUFFER_SIZE / 1024, TCP_SND_BUFFER_SIZE / 1024, TCP_BACKLOG_SIZE);
    }
    
    /**
     * 初始化客户端TCP优化配置
     * 为客户端Socket设置优化的TCP参数
     * 
     * @param bootstrap 客户端引导类
     */
    public static void initClientOptimizedOptions(Bootstrap bootstrap) {
        if (bootstrap == null) {
            log.warn("Bootstrap is null, cannot set TCP options");
            return;
        }
        
        // 客户端Socket选项
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                 .option(ChannelOption.TCP_NODELAY, true)
                 .option(ChannelOption.SO_KEEPALIVE, true)
                 .option(ChannelOption.SO_RCVBUF, TCP_RCV_BUFFER_SIZE)
                 .option(ChannelOption.SO_SNDBUF, TCP_SND_BUFFER_SIZE)
                 .option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                     new io.netty.channel.WriteBufferWaterMark(
                         WRITE_BUFFER_WATER_MARK_LOW, WRITE_BUFFER_WATER_MARK_HIGH))
                 .option(ChannelOption.ALLOCATOR, io.netty.buffer.PooledByteBufAllocator.DEFAULT);
        
        log.debug("Client TCP optimized options configured: RCV_BUF={}KB, SND_BUF={}KB", 
                 TCP_RCV_BUFFER_SIZE / 1024, TCP_SND_BUFFER_SIZE / 1024);
    }
    
    /**
     * 获取针对大数据传输的TCP配置
     * 适用于文件传输等大数据量场景
     * 
     * @return 优化后的Bootstrap配置对象（包含TCP选项）
     */
    public static Bootstrap getLargeDataTransferOptions() {
        Bootstrap bootstrap = new Bootstrap();
        
        // 大数据传输需要更大的缓冲区
        bootstrap.option(ChannelOption.TCP_NODELAY, true)
                 .option(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024)  // 4MB接收缓冲区
                 .option(ChannelOption.SO_SNDBUF, 4 * 1024 * 1024)  // 4MB发送缓冲区
                 .option(ChannelOption.SO_KEEPALIVE, true)
                 .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
        
        log.debug("Large data transfer TCP options configured: 4MB buffers");
        return bootstrap;
    }
    
    /**
     * 获取针对低延迟场景的TCP配置
     * 适用于实时通信等低延迟场景
     * 
     * @return 优化后的Bootstrap配置对象（包含TCP选项）
     */
    public static Bootstrap getLowLatencyOptions() {
        Bootstrap bootstrap = new Bootstrap();
        
        // 低延迟场景需要快速响应
        bootstrap.option(ChannelOption.TCP_NODELAY, true)
                 .option(ChannelOption.SO_RCVBUF, 64 * 1024)  // 64KB接收缓冲区
                 .option(ChannelOption.SO_SNDBUF, 64 * 1024)  // 64KB发送缓冲区
                 .option(ChannelOption.SO_KEEPALIVE, true)
                 .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000);  // 更短的连接超时
        
        log.debug("Low latency TCP options configured: 64KB buffers");
        return bootstrap;
    }
    
    /**
     * 应用高级TCP选项配置（系统级别）
     * 注意：某些选项可能需要操作系统支持
     * 
     * @param channel 要配置的SocketChannel
     * @return 配置是否成功
     */
    public static boolean applyAdvancedTcpOptions(SocketChannel channel) {
        if (channel == null) {
            log.warn("SocketChannel is null, cannot apply advanced options");
            return false;
        }
        
        try {
            // 尝试设置TCP快速打开（需要Linux 3.7+）
            channel.config().setOption(ChannelOption.TCP_FASTOPEN_CONNECT, true);
            log.debug("TCP fast open option applied");
            
            return true;
        } catch (Exception e) {
            log.warn("Failed to apply advanced TCP options: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 根据网络条件动态调整TCP缓冲区大小
     * 根据延迟和带宽自动调整缓冲区大小
     * 
     * @param rttMs 往返延迟（毫秒）
     * @param bandwidthMbps 带宽（Mbps）
     * @return 推荐缓冲区大小（字节）
     */
    public static int calculateOptimalBufferSize(int rttMs, int bandwidthMbps) {
        // BDP = bandwidth * RTT
        // TCP缓冲区应设置为BDP的1-2倍以充分利用带宽
        int bdpBits = bandwidthMbps * 1000 * 1000 * rttMs / 1000;  // 带宽（比特） * RTT（秒）
        int bdpBytes = bdpBits / 8;  // 转换为字节
        
        // 最小缓冲区：64KB，最大缓冲区：4MB
        int minBuffer = 64 * 1024;
        int maxBuffer = 4 * 1024 * 1024;
        
        int recommendedBuffer = Math.min(Math.max(bdpBytes * 2, minBuffer), maxBuffer);
        
        log.debug("Network condition: RTT={}ms, Bandwidth={}Mbps, Recommended buffer={}KB", 
                 rttMs, bandwidthMbps, recommendedBuffer / 1024);
        
        return recommendedBuffer;
    }
}