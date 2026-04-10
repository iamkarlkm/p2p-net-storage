package javax.net.p2p.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import javax.net.p2p.channel.PipelineInitializer;
import javax.net.p2p.codec.P2PWrapperEncoder;
import lombok.extern.slf4j.Slf4j;
/**
 * P2PServerTcp。
 */

@Slf4j
public class P2PServerTcp {
    
    public static int SERVER_PORT = 6060;

    private final int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public P2PServerTcp(int port) {
        this.port = port;
    }

    public void start() {
        if (bossGroup != null && workerGroup != null) {
            throw new RuntimeException("同一服务正在运行，请先关闭后再操作,监听端口->" + port);
        }
        ServerMessageProcessor.registerProcessors();
//        try {
//            HdfsUtil.initFileSysytem();
//        } catch (IOException ex) {
//            log.error(ex.getMessage(), ex);
//        }
        bossGroup = new NioEventLoopGroup();
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap server = new ServerBootstrap();

            server.group(bossGroup, workerGroup)
                // 设置非阻塞,用它来建立新accept的连接,用于构造ServerSocketChannel的工厂类
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {

                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        PipelineInitializer.initProcessors(ch, new ServerMessageProcessor(P2PWrapperEncoder.MAGIC,4096));
                    }
                });

            PipelineInitializer.initServerOptions(server);

            ChannelFuture future = server.bind(port).sync();
            log.info("P2P server started listen at {}", port);
            future.channel().closeFuture().sync();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        } finally {
            if (bossGroup != null && workerGroup != null) {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
                try {
//                HdfsUtil.closeFileSysytem();
                 //   CosUtil.shutdown();
                } catch (Exception ex) {
                    log.error(ex.getMessage(), ex);
                }
            } else {
                System.out.println("P2P server already closed: " + port);
            }
            System.out.println("P2P server finnaly...");
        }
    }

    public void stop() {
        if (bossGroup != null && workerGroup != null) {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            try {
//                HdfsUtil.closeFileSysytem();
                //CosUtil.shutdown();
            } catch (Exception ex) {
                log.error(ex.getMessage(), ex);
            }
        } else {
            System.out.println("P2P server already closed: " + port);
        }
    }

    public static void main(String[] args) throws Exception {
    System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
    
        new P2PServerTcp(SERVER_PORT).start();
    }
}
