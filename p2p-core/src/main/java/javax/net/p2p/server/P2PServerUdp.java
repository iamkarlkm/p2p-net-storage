package javax.net.p2p.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import javax.net.p2p.channel.PipelineInitializer;
import javax.net.p2p.common.ExecutorServicePool;

/**
 *
 * @author karl
 */
public class P2PServerUdp extends AbstractP2PServer {
    
    
        
    public P2PServerUdp(Integer port) {
        super(port);
    }
    
    public P2PServerUdp(int port, int magic) {
        super(port, DEFAULT_QUEUESIZE, DEFAULT_CORESIZE, magic);
    }
    
    public P2PServerUdp(int port, int queueSize, int magic) {
        super(port, queueSize, DEFAULT_CORESIZE, magic);
    }
    
     
    
    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
        P2PServerUdp server = P2PServerUdp.getInstance(P2PServerUdp.class,10086);
        //...
        server.released();
    }
    
    @Override
    public void singletonCreated(Object inatance) {
        if (acceptBossGroup != null) {
            throw new RuntimeException("同一服务正在运行，请先关闭后再操作,监听端口->" + port);
        }
        try {
            ExecutorServicePool.createServerPools();
            server = new Bootstrap();
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
                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel ch)
                                throws Exception {
                            //PipelineInitializer.initProcessorsUdp(ch, new ServerUdpMessageProcessor(defaultMagic,queueSize));
                            PipelineInitializer.initProcessorsUdpWithReliability(ch, new ServerUdpMessageProcessor((P2PServerUdp) inatance, magic, queueSize), magic,true);
                        }
                    });
            PipelineInitializer.initClientOptionsUdp(server);
            // 绑定端口，开始接收进来的连接
//           InetSocketAddress address = new InetSocketAddress(InetAddress.getLocalHost(), port);
            ChannelFuture future = server.bind(port).sync();
            System.out.println("Udp P2P server start listen at " + port);
            System.out.println("future.channel().remoteAddress()="+future.channel().remoteAddress());
            //future.channel().closeFuture().sync();
            //ServerUdpMessageProcessor serverUdpMessageProcessor = ChannelUtils.getServerUdpMessageProcessor(future.channel());
//           让线程进入wait状态，也就是main线程暂时不会执行到finally里面，nettyserver也持续运行，如果监听到关闭事件，可以优雅的关闭通道和nettyserver
            future.channel().closeFuture().await();
            System.out.println("after future.channel().closeFuture().await() future.channel().remoteAddress()="+future.channel().remoteAddress());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.out.println("released");
            released();
        }
        super.singletonCreated(this);
    }
    
    /**
     * 引用归零，回调，关闭udp服务器资源
     */
    @Override
    public void singletonFinalized() {
        if (acceptBossGroup != null) {
            acceptBossGroup.shutdownGracefully();
            acceptBossGroup = null;
         

        } else {
            System.out.println("Udp P2P server already closed: " + port);
        }
        super.singletonFinalized();
    }
    
  
 
}
