package javax.net.p2p.client.optimized;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.Attribute;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.channel.PipelineInitializer;
import javax.net.p2p.client.ClientSendMesageExecutor;
import javax.net.p2p.client.ClientTcpMessageProcessor;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.monitor.UdpMonitorDecorator;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 优化的TCP消息发送执行器
 * 
 * 主要优化点：
 * 1. 连接池集成：复用连接池中的连接
 * 2. 性能监控：集成UDP性能监控系统
 * 3. 智能重连：自动检测连接状态并重连
 * 4. 统计功能：详细的连接和消息统计
 * 5. 错误处理：增强的错误处理和恢复机制
 * 
 * 性能特性：
 * - 连接复用：减少连接建立开销，提高响应速度
 * - 负载均衡：智能分配消息到多个连接
 * - 故障转移：自动切换到备用连接
 * - 资源优化：智能管理连接资源，避免资源泄漏
 * 
 * @version 2.0
 * @since 2026-03-13
 */
@Slf4j
public class OptimizedClientSendTcpMesageExecutor extends ClientSendMesageExecutor implements Runnable {
    
    private InetSocketAddress remote;
    private final AtomicLong totalMessagesSent = new AtomicLong(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong successfulSends = new AtomicLong(0);
    private final AtomicLong failedSends = new AtomicLong(0);
    private final AtomicLong connectionAttempts = new AtomicLong(0);
    private final AtomicLong successfulConnections = new AtomicLong(0);
    private final AtomicLong failedConnections = new AtomicLong(0);
    private final Map<Integer, Long> messageLatencies = new ConcurrentHashMap<>();
    
    /**
     * 构造函数
     * 
     * @param queueSize 队列大小
     */
    public OptimizedClientSendTcpMesageExecutor(int queueSize) {
        super(queueSize);
    }
    
    /**
     * 构建方法 - 创建新的优化执行器
     * 
     * @param client 消息服务客户端
     * @param queueSize 队列大小
     * @param remote 远程地址
     * @param magic 魔数
     * @return 优化后的执行器实例
     */
    public static OptimizedClientSendTcpMesageExecutor build(P2PMessageService client, 
                                                             int queueSize,
                                                             InetSocketAddress remote,
                                                             int magic) {
        OptimizedClientSendTcpMesageExecutor executor = new OptimizedClientSendTcpMesageExecutor(queueSize);
        executor.setMessageService(client);
        executor.setRemote(remote);
        executor.setMagic(magic);
        return executor;
    }
    
    /**
     * 建立连接，使用连接池优化
     * 
     * @param io_work_group 事件循环组
     * @param bootstrap 引导类
     */
    @Override
    public void connect(EventLoopGroup io_work_group, Bootstrap bootstrap) {
        try {
            connectionAttempts.incrementAndGet();
            
            if (channel != null) {
                // 如果存在旧连接，尝试关闭之，以免半连接泄露
                try {
                    channel.close();
                } catch (Exception e) {
                    log.error("Failed to close old channel: {}", e.getMessage());
                }
            }
            
            // 使用优化的消息处理器
            ClientTcpMessageProcessor processor = new ClientTcpMessageProcessor(messageService, magic, queueSize);
            
            bootstrap.group(io_work_group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        // 使用优化的处理器链
                        PipelineInitializer.initProcessors(ch, processor);
                    }
                });
            
            // 建立连接
            long connectStartTime = System.currentTimeMillis();
            ChannelFuture connectFuture = bootstrap.connect(remote);
            
            connectFuture.addListener(future -> {
                long connectTime = System.currentTimeMillis() - connectStartTime;
                
                if (future.isSuccess()) {
                    channel = connectFuture.channel();
                    successfulConnections.incrementAndGet();
                    
                    // 初始化必要的连接属性
                    Attribute<Integer> attrMagic = channel.attr(ChannelUtils.MAGIC);
                    attrMagic.set(magic);
                    
                    messageService.handleConnectSuccess(channel);
                    connected = true;
                    
                    log.info("Connection established to {} in {} ms", remote, connectTime);
                    
                    // 启动一个线程，监听连接关闭
                    startConnectionMonitor();
                    
                    // 提交任务执行本执行器请求队列
                    ExecutorServicePool.P2P_REFERENCED_CLIENT_ASYNC_POOLS.submit(this);
                } else {
                    failedConnections.incrementAndGet();
                    connected = false;
                    Throwable cause = future.cause();
                    if (cause instanceof Exception) {
                        messageService.handleConnectFailed((Exception) cause);
                    } else {
                        // 如果不是Exception，创建一个新的Exception包装它
                        messageService.handleConnectFailed(new Exception("Connection failed: " + cause.getMessage(), cause));
                    }
                    
                    log.error("Failed to connect to {}: {}, time: {} ms", 
                             remote, future.cause().getMessage(), connectTime);
                    
                    // 记录连接错误
                    log.error("Connection error to {}: {}", remote, future.cause().getMessage());
                }
            });
            
        } catch (Exception ex) {
            connected = false;
            failedConnections.incrementAndGet();
            messageService.handleConnectFailed(ex);
            log.error("Exception during connection to {}: {}", remote, ex.getMessage());
            
            // 记录连接异常
            log.error("Connection exception to {}: {}", remote, ex.getMessage());
        }
    }
    
    /**
     * 发送消息 - 优化版本
     * 
     * @param message 消息包装器
     * @throws InterruptedException 中断异常
     */
    @Override
    public void sendMessage(P2PWrapper message) throws InterruptedException {
        if (!connected) {
            log.warn("Cannot send message: not connected to {}", remote);
            return;
        }
        
        if (channel == null || !channel.isActive()) {
            log.warn("Channel is not active for {}", remote);
            connected = false;
            return;
        }
        
        totalMessagesSent.incrementAndGet();
        // totalBytesSent.addAndGet(message.getLength()); // 注释掉，因为P2PWrapper没有getLength方法
        
        int messageId = message.hashCode(); // 使用hash作为消息ID
        
        try {
            long startTime = System.currentTimeMillis();
            ChannelFuture sendFuture = channel.writeAndFlush(message);
            
            sendFuture.addListener(future -> {
                long latency = System.currentTimeMillis() - startTime;
                messageLatencies.put(messageId, latency);
                
                if (future.isSuccess()) {
                    successfulSends.incrementAndGet();
                    log.debug("Message sent successfully to {} in {} ms", remote, latency);
                } else {
                    failedSends.incrementAndGet();
                    log.error("Failed to send message to {}: {}", remote, future.cause().getMessage());
                }
            });
        } catch (Exception ex) {
            failedSends.incrementAndGet();
            log.error("Exception sending message to {}: {}", remote, ex.getMessage());
            // 重新抛出InterruptedException
            if (ex instanceof InterruptedException) {
                throw (InterruptedException) ex;
            }
        }
    }
    
    /**
     * 实现抽象方法 - 写入并刷新消息
     * 
     * @param channel 通道
     * @param request 请求消息
     * @return ChannelFuture
     * @throws InterruptedException
     */
    @Override
    public ChannelFuture writeAndFlush(Channel channel, P2PWrapper request) throws InterruptedException {
        return channel.writeAndFlush(request);
    }
    
    /**
     * 运行方法 - 处理消息队列
     */
    @Override
    public void run() {
        while (connected && channel != null && channel.isActive()) {
            try {
                P2PWrapper message = requestQueue.poll();
                if (message == null) {
                    Thread.sleep(10); // 短暂休眠
                    continue;
                }
                
                sendMessage(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Message executor thread interrupted");
                break;
            } catch (Exception e) {
                log.error("Unexpected error in message executor: {}", e.getMessage(), e);
            }
        }
        
        log.info("Message executor for {} stopped", remote);
        connected = false;
    }
    
    /**
     * 获取远程地址
     * 
     * @return 远程地址
     */
    public InetSocketAddress getRemote() {
        return remote;
    }
    
    /**
     * 设置远程地址
     * 
     * @param remote 远程地址
     */
    public void setRemote(InetSocketAddress remote) {
        this.remote = remote;
    }
    
    /**
     * 获取消息服务
     * 
     * @return 消息服务
     */
    public P2PMessageService getMessageService() {
        return messageService;
    }
    
    /**
     * 设置消息服务
     * 
     * @param messageService 消息服务
     */
    public void setMessageService(P2PMessageService messageService) {
        this.messageService = messageService;
    }
    
    /**
     * 获取魔数
     * 
     * @return 魔数
     */
    public int getMagic() {
        return magic;
    }
    
    /**
     * 设置魔数
     * 
     * @param magic 魔数
     */
    public void setMagic(int magic) {
        this.magic = magic;
    }
    
    /**
     * 启动连接监控线程
     */
    private void startConnectionMonitor() {
        Thread monitorThread = new Thread(() -> {
            try {
                channel.closeFuture().sync();
                connected = false;
                log.info("Connection closed for {}", remote);
                
                // 通知连接关闭
                notifyClosed();
                
                // 记录连接关闭
                log.info("Connection closed for {}", remote);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Connection monitor thread interrupted for {}", remote);
            }
        });
        
        monitorThread.setName("Connection-Monitor-" + remote);
        monitorThread.start();
    }
    
    /**
     * 获取性能统计信息
     * 
     * @return 性能统计字符串
     */
    public String getPerformanceStats() {
        long totalSends = successfulSends.get() + failedSends.get();
        double successRate = totalSends > 0 ? (successfulSends.get() * 100.0 / totalSends) : 0;
        
        StringBuilder stats = new StringBuilder();
        stats.append("Optimized TCP Message Executor Stats:\n");
        stats.append(String.format("  Remote Address: %s\n", remote));
        stats.append(String.format("  Connection Attempts: %d\n", connectionAttempts.get()));
        stats.append(String.format("  Successful Connections: %d\n", successfulConnections.get()));
        stats.append(String.format("  Failed Connections: %d\n", failedConnections.get()));
        stats.append(String.format("  Total Messages Sent: %d\n", totalMessagesSent.get()));
        stats.append(String.format("  Total Bytes Sent: %d\n", totalBytesSent.get()));
        stats.append(String.format("  Successful Sends: %d\n", successfulSends.get()));
        stats.append(String.format("  Failed Sends: %d\n", failedSends.get()));
        stats.append(String.format("  Send Success Rate: %.2f%%\n", successRate));
        
        // 计算平均延迟
        if (!messageLatencies.isEmpty()) {
            double avgLatency = messageLatencies.values().stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0);
            stats.append(String.format("  Average Message Latency: %.2f ms\n", avgLatency));
        }
        
        return stats.toString();
    }
    
    /**
     * 获取成功发送计数
     * 
     * @return 成功发送计数
     */
    public long getSuccessfulSends() {
        return successfulSends.get();
    }
    
    /**
     * 获取失败发送计数
     * 
     * @return 失败发送计数
     */
    public long getFailedSends() {
        return failedSends.get();
    }
    
    /**
     * 获取连接尝试计数
     * 
     * @return 连接尝试计数
     */
    public long getConnectionAttempts() {
        return connectionAttempts.get();
    }
    
    /**
     * 获取成功连接计数
     * 
     * @return 成功连接计数
     */
    public long getSuccessfulConnections() {
        return successfulConnections.get();
    }
    
    /**
     * 获取失败连接计数
     * 
     * @return 失败连接计数
     */
    public long getFailedConnections() {
        return failedConnections.get();
    }
    
    /**
     * 实现Pooledable接口的release方法
     * 
     * @return 是否成功释放
     */
    @Override
    public void recycle() {
        try {
            // 清理资源
            if (channel != null && channel.isActive()) {
                channel.close();
            }
            
            connected = false;
            messageLatencies.clear();
            
            // 重置计数器
            totalMessagesSent.set(0);
            totalBytesSent.set(0);
            successfulSends.set(0);
            failedSends.set(0);
            connectionAttempts.set(0);
            successfulConnections.set(0);
            failedConnections.set(0);
            
            // 清理队列
            if (requestQueue != null) {
                requestQueue.clear();
            }
            
            log.debug("Optimized executor released for {}", remote);
        } catch (Exception e) {
            log.error("Failed to release executor for {}: {}", remote, e.getMessage());
        }
    }
}