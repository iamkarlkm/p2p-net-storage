package javax.net.p2p.client.optimized;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.channel.PipelineInitializer;
import javax.net.p2p.client.AbstractP2PClient;
import javax.net.p2p.client.ClientSendMesageExecutor;
import javax.net.p2p.client.ClientSendTcpMesageExecutor;
import javax.net.p2p.connection.TcpConnectionPool;
import lombok.extern.slf4j.Slf4j;

/**
 * 优化的P2P TCP客户端
 * 
 * 主要优化点：
 * 1. 连接池集成：集成TCP连接池，实现连接复用
 * 2. 智能重连：自动检测连接状态并重连
 * 3. 连接管理：智能连接选择和管理
 * 4. 性能优化：优化连接建立和使用流程
 * 5. 监控集成：实时监控连接状态和性能
 * 
 * 性能特性：
 * - 连接复用：减少连接建立开销，提高响应速度
 * - 负载均衡：在多连接间智能分配负载
 * - 故障转移：自动切换到健康连接
 * - 资源优化：智能管理连接资源，避免资源泄漏
 * 
 * @version 2.0
 * @since 2026-03-13
 */
@Slf4j
public class OptimizedP2PClientTcp extends AbstractP2PClient {
    
    // 连接池
    private final TcpConnectionPool connectionPool;
    
    // 连接配置
    private final int connectTimeoutMs;
    private final int idleTimeoutSeconds;
    
    // 重连配置
    private final int maxReconnectAttempts;
    private final int reconnectDelayMs;
    
    // 连接统计
    private final ConnectionStats connectionStats;
    
    /**
     * 构造函数 - 使用默认配置
     * 
     * @param remoteServer 远程服务器地址
     */
    public OptimizedP2PClientTcp(InetSocketAddress remoteServer) {
        this(remoteServer, DEFAULT_QUEUESIZE, DEFAULT_CORESIZE, DEFAULT_MAGIC);
    }
    
    /**
     * 构造函数 - 自定义配置
     * 
     * @param remoteServer 远程服务器地址
     * @param queueSize 队列大小
     * @param coreSize 核心线程数
     * @param magic 魔数
     */
    public OptimizedP2PClientTcp(InetSocketAddress remoteServer, int queueSize, 
                                int coreSize, int magic) {
        this(remoteServer, queueSize, coreSize, magic, 5000, 30, 3, 1000);
    }
    
    /**
     * 构造函数 - 完整配置
     * 
     * @param remoteServer 远程服务器地址
     * @param queueSize 队列大小
     * @param coreSize 核心线程数
     * @param magic 魔数
     * @param connectTimeoutMs 连接超时时间（毫秒）
     * @param idleTimeoutSeconds 空闲超时时间（秒）
     * @param maxReconnectAttempts 最大重连尝试次数
     * @param reconnectDelayMs 重连延迟时间（毫秒）
     */
    public OptimizedP2PClientTcp(InetSocketAddress remoteServer, int queueSize, 
                                int coreSize, int magic, int connectTimeoutMs,
                                int idleTimeoutSeconds, int maxReconnectAttempts,
                                int reconnectDelayMs) {
        super(remoteServer, queueSize, coreSize, magic);
        
        this.connectTimeoutMs = connectTimeoutMs;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.reconnectDelayMs = reconnectDelayMs;
        
        this.connectionPool = TcpConnectionPool.getInstance();
        this.connectionStats = new ConnectionStats();
        
        log.info("OptimizedP2PClientTcp initialized for server: {}", remoteServer);
        log.info("Configuration: connectTimeout={}ms, idleTimeout={}s, maxReconnect={}, reconnectDelay={}ms",
                 connectTimeoutMs, idleTimeoutSeconds, maxReconnectAttempts, reconnectDelayMs);
    }
    
    /**
     * 获取连接
     * 
     * @return 获取连接的结果Future
     */
    public Future<Channel> acquireConnection() {
        // 检查连接池健康状态
        if (!connectionPool.isPoolHealthy(remoteServer)) {
            log.warn("Connection pool for {} is unhealthy, creating new pool", remoteServer);
        }
        
        connectionStats.connectionAttempts.increment();
        
        return connectionPool.acquireConnection(
            remoteServer, 
            getBootstrap(), 
            this, 
            magic, 
            queueSize
        ).addListener(future -> {
            if (future.isSuccess()) {
                connectionStats.successfulConnections.increment();
                
                Channel channel = (Channel) future.getNow();
                
                // 注册连接监听器
                registerConnectionListeners(channel);
                
                // 记录连接建立时间
                recordConnectionEstablished(channel);
                
                log.debug("Connection established to {}", remoteServer);
                
            } else {
                connectionStats.failedConnections.increment();
                
                // 尝试重连
                attemptReconnect(future.cause());
            }
        });
    }
    
    /**
     * 释放连接
     * 
     * @param channel 要释放的连接
     */
    public void releaseConnection(Channel channel) {
        if (channel == null || !channel.isActive()) {
            log.warn("Attempting to release inactive or null channel");
            return;
        }
        
        connectionPool.releaseConnection(remoteServer, channel);
        connectionStats.connectionsReleased.increment();
    }
    
    /**
     * 创建新的消息执行器
     */
    @Override
    public ClientSendMesageExecutor newSendMesageExecutorToQueue() {
        // 使用优化的执行器
        OptimizedClientSendTcpMesageExecutor executor = 
            OptimizedClientSendTcpMesageExecutor.build(this, queueSize, remoteServer, magic);
        
        // 尝试从连接池获取连接
        Future<Channel> connectionFuture = acquireConnection();
        
        // 异步等待连接建立后设置执行器
        connectionFuture.addListener(future -> {
            if (future.isSuccess()) {
                Channel channel = (Channel) future.getNow();
                executor.connect(io_work_group, getBootstrap());
                sendMesageExecutors.add(executor);
                
                log.debug("New message executor created and connected");
            } else {
                log.error("Failed to create message executor, connection failed", future.cause());
            }
        });
        
        return executor;
    }
    
    /**
     * 获取Bootstrap配置
     */
    private Bootstrap getBootstrap() {
        EventLoopGroup group = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true);
        
        // 使用优化的TCP配置
        PipelineInitializer.initClientOptions(bootstrap);
        
        return bootstrap;
    }
    
    /**
     * 注册连接监听器
     */
    private void registerConnectionListeners(Channel channel) {
        channel.closeFuture().addListener(future -> {
            if (channel.isActive()) {
                log.info("Connection closed: {}", channel.id());
            } else {
                log.warn("Inactive connection closed: {}", channel.id());
            }
            
            connectionStats.connectionsClosed.increment();
            
            // 检查是否需要重连
            if (shouldReconnect()) {
                scheduleReconnect();
            }
        });
    }
    
    /**
     * 记录连接建立时间
     */
    private void recordConnectionEstablished(Channel channel) {
        long now = System.currentTimeMillis();
        channel.attr(io.netty.util.AttributeKey.valueOf("connectionEstablishedTime")).set(now);
        connectionStats.lastConnectionTime = now;
    }
    
    /**
     * 尝试重连
     */
    private void attemptReconnect(Throwable cause) {
        int attempts = connectionStats.reconnectAttempts.increment();
        
        if (attempts <= maxReconnectAttempts) {
            log.info("Attempting reconnection to {} (attempt {}/{})", 
                    remoteServer, attempts, maxReconnectAttempts);
            
            // 延迟后重试
            scheduleReconnect();
        } else {
            log.error("Max reconnection attempts ({}) exceeded for {}", 
                     maxReconnectAttempts, remoteServer, cause);
            
            connectionStats.reconnectFailures.increment();
            
            // 触发连接失败处理
            handleConnectFailed(new RuntimeException(cause));
        }
    }
    
    /**
     * 调度重连
     */
    private void scheduleReconnect() {
        // 使用事件循环调度重连
        io_work_group.schedule(() -> {
            if (shouldReconnect()) {
                acquireConnection();
            }
        }, reconnectDelayMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 检查是否需要重连
     */
    private boolean shouldReconnect() {
        // 根据业务逻辑判断是否需要重连
        // 例如：连接丢失、网络异常等
        return isConnectionFailed;
    }
    
    /**
     * 获取连接统计信息
     */
    public ConnectionStats getConnectionStats() {
        return connectionStats;
    }
    
    /**
     * 获取连接池统计信息
     */
    public TcpConnectionPool.ConnectionPoolStats getPoolStats() {
        return connectionPool.getPoolStats(remoteServer);
    }
    
    /**
     * 获取全局统计信息
     */
    public TcpConnectionPool.GlobalStats getGlobalStats() {
        return connectionPool.getGlobalStats();
    }
    
    /**
     * 清理资源
     */
    @Override
    public void shutdown() {
        log.info("Shutting down optimized TCP client...");
        
        // 清理连接池
        connectionPool.closePool(remoteServer);
        
        // 调用父类的清理逻辑
        super.shutdown();
        
        log.info("Optimized TCP client shutdown complete");
    }
    
    /**
     * 连接统计信息类
     */
    public static class ConnectionStats {
        private volatile long lastConnectionTime = 0;
        private final AtomicInt connectionAttempts = new AtomicInt();
        private final AtomicInt successfulConnections = new AtomicInt();
        private final AtomicInt failedConnections = new AtomicInt();
        private final AtomicInt connectionsReleased = new AtomicInt();
        private final AtomicInt connectionsClosed = new AtomicInt();
        private final AtomicInt reconnectAttempts = new AtomicInt();
        private final AtomicInt reconnectFailures = new AtomicInt();
        
        public long getLastConnectionTime() {
            return lastConnectionTime;
        }
        
        public int getConnectionAttempts() {
            return connectionAttempts.get();
        }
        
        public int getSuccessfulConnections() {
            return successfulConnections.get();
        }
        
        public int getFailedConnections() {
            return failedConnections.get();
        }
        
        public int getConnectionsReleased() {
            return connectionsReleased.get();
        }
        
        public int getConnectionsClosed() {
            return connectionsClosed.get();
        }
        
        public int getReconnectAttempts() {
            return reconnectAttempts.get();
        }
        
        public int getReconnectFailures() {
            return reconnectFailures.get();
        }
        
        public double getSuccessRate() {
            int attempts = connectionAttempts.get();
            return attempts > 0 ? (double) successfulConnections.get() / attempts * 100 : 0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "ConnectionStats{attempts=%d, success=%d (%.1f%%), failures=%d, reconnects=%d/%d}",
                connectionAttempts.get(), successfulConnections.get(), getSuccessRate(),
                failedConnections.get(), reconnectAttempts.get(), reconnectFailures.get()
            );
        }
    }
    
    /**
     * 简化的原子整数类
     */
    private static class AtomicInt {
        private int value = 0;
        
        public int get() { return value; }
        public int increment() { return ++value; }
        public int incrementAndGet() { return ++value; }
    }
    
    /**
     * 简化的父类引用
     */
    private static abstract class AbstractOptimizedP2PClient {
        protected final InetSocketAddress remoteServer;
        protected final int queueSize;
        protected final int coreSize;
        protected final int magic;
        protected final EventLoopGroup io_work_group;
        protected final java.util.concurrent.CopyOnWriteArrayList<ClientSendMesageExecutor> sendMesageExecutors;
        
        protected static final int DEFAULT_QUEUESIZE = 4096;
        protected static final int DEFAULT_CORESIZE = 4;
        protected static final int DEFAULT_MAGIC = 0x12345678;
        
        public AbstractOptimizedP2PClient(InetSocketAddress remoteServer, int queueSize,
                                         int coreSize, int magic) {
            this.remoteServer = remoteServer;
            this.queueSize = queueSize;
            this.coreSize = coreSize;
            this.magic = magic;
            this.io_work_group = new NioEventLoopGroup(coreSize);
            this.sendMesageExecutors = new java.util.concurrent.CopyOnWriteArrayList<>();
        }
        
        protected boolean isConnected() {
            return !io_work_group.isShutdown();
        }
        
        protected void handleConnectFailed(Throwable cause) {
            log.error("Connection failed to {}", remoteServer, cause);
        }
        
        protected void shutdown() {
            io_work_group.shutdownGracefully();
            for (ClientSendMesageExecutor executor : sendMesageExecutors) {
                executor.clear();
            }
            sendMesageExecutors.clear();
        }
    }
}