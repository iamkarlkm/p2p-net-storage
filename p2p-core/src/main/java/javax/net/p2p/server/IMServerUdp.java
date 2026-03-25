package javax.net.p2p.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import javax.net.p2p.channel.PipelineInitializer;
import javax.net.p2p.common.ExecutorServicePool;
import lombok.extern.slf4j.Slf4j;

/**
 * IM服务端UDP实现
 * 继承自AbstractP2PServer，处理IM消息
 */
@Slf4j
public class IMServerUdp extends AbstractP2PServer {

    public IMServerUdp(Integer port) {
        super(port);
    }
    
    public IMServerUdp(int port, int magic) {
        super(port, DEFAULT_QUEUESIZE, DEFAULT_CORESIZE, magic);
    }
    
    public IMServerUdp(int port, int queueSize, int magic) {
        super(port, queueSize, DEFAULT_CORESIZE, magic);
    }

    public IMServerUdp(int port, int queueSize, int coreSize, int magic) {
        super(port, queueSize, coreSize, magic);
    }

    @Override
    public void singletonCreated(Object instance) {
        if (acceptBossGroup != null) {
            throw new RuntimeException("IM服务正在运行，请先关闭后再操作,监听端口->" + port);
        }
        try {
            ExecutorServicePool.createServerPools();
            server = new Bootstrap();
            
            // 根据操作系统选择EventLoopGroup
            final boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");
            final boolean isLinux = System.getProperty("os.name").toLowerCase().contains("linux");
            
            if (isMac) {
                acceptBossGroup = new io.netty.channel.kqueue.KQueueEventLoopGroup();
            } else if (isLinux) {
                acceptBossGroup = new io.netty.channel.epoll.EpollEventLoopGroup();
            } else {
                acceptBossGroup = new NioEventLoopGroup();
            }
            
            server.group(acceptBossGroup)
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<io.netty.channel.socket.nio.NioDatagramChannel>() {
                        @Override
                        protected void initChannel(io.netty.channel.socket.nio.NioDatagramChannel ch) throws Exception {
                            // 初始化UDP处理器，启用可靠性传输
                            PipelineInitializer.initProcessorsUdpWithReliability(ch, 
                                new ServerUdpMessageProcessor((AbstractP2PServer) instance, magic, queueSize), 
                                magic, true);
                        }
                    });
            
            PipelineInitializer.initClientOptionsUdp(server);
            
            // 绑定端口
            ChannelFuture future = server.bind(port).sync();
            log.info("IM UDP Server started at port: {}", port);
            
            // 异步等待关闭
            future.channel().closeFuture().addListener(f -> {
                log.info("IM UDP Server channel closed");
                released();
            });
            
        } catch (Exception e) {
            log.error("Failed to start IM UDP Server", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void singletonFinalized() {
        if (acceptBossGroup != null) {
            acceptBossGroup.shutdownGracefully();
            acceptBossGroup = null;
            ExecutorServicePool.releaseP2PServerPools();
            log.info("IM UDP Server finalized and resources released");
        } else {
            log.warn("IM UDP Server already closed: {}", port);
        }
    }
    
    /**
     * 获取IMServer实例
     */
    public static IMServerUdp getInstance(int port) throws UnknownHostException {
        return getInstance(IMServerUdp.class, port);
    }
}
