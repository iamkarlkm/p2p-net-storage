package javax.net.p2p.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.common.ExecutorServicePool;

/**
 *
 * @author karl
 */
public class P2PServerQuic extends AbstractP2PServer {

    public P2PServerQuic(Integer port) {
        super(port);
    }

    public P2PServerQuic(int port, int magic) {
        super(port, DEFAULT_QUEUESIZE, DEFAULT_CORESIZE, magic);
    }

    public P2PServerQuic(int port, int queueSize, int magic) {
        super(port, queueSize, DEFAULT_CORESIZE, magic);
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"));
        P2PServerQuic server = P2PServerQuic.getInstance(P2PServerQuic.class, 10086);
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

            SelfSignedCertificate selfSignedCertificate = new SelfSignedCertificate();
            QuicSslContext context = QuicSslContextBuilder.forServer(
                    selfSignedCertificate.privateKey(), null, selfSignedCertificate.certificate())
                    .applicationProtocols("http/0.9").build();

            ChannelHandler codec = new QuicServerCodecBuilder().sslContext(context)
                    .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                    .initialMaxData(10000000)
                    .initialMaxStreamDataBidirectionalLocal(1000000)
                    .initialMaxStreamDataBidirectionalRemote(1000000)
                    .initialMaxStreamsBidirectional(100)
                    .initialMaxStreamsUnidirectional(100)
                    .handler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            QuicChannel channel = (QuicChannel) ctx.channel();
                            // set magic attribute
                            channel.attr(ChannelUtils.MAGIC).set(magic);
                        }

                        @Override
                        public boolean isSharable() {
                            return true;
                        }
                    })
                    .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel ch) {
                            ch.attr(ChannelUtils.MAGIC).set(magic);
                            ch.pipeline().addLast(new ServerQuicMessageProcessor((P2PServerQuic) inatance, magic, queueSize));
                        }
                    }).build();

            server.group(acceptBossGroup)
                    .channel(NioDatagramChannel.class)
                    .handler(codec);

            ChannelFuture future = server.bind(port).sync();
            System.out.println("Quic P2P server start listen at " + port);
            System.out.println("future.channel().remoteAddress()=" + future.channel().remoteAddress());

            future.channel().closeFuture().await();
            System.out.println("after future.channel().closeFuture().await() future.channel().remoteAddress()=" + future.channel().remoteAddress());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.out.println("released");
        }
        super.singletonCreated(this);
    }

    @Override
    public void singletonFinalized() {
        if (acceptBossGroup != null) {
            acceptBossGroup.shutdownGracefully();
            acceptBossGroup = null;
        } else {
            System.out.println("Quic P2P server already closed: " + port);
        }
        super.singletonFinalized();
    }
}
