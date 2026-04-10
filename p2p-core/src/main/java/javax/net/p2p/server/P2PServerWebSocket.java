package javax.net.p2p.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.codec.P2PWrapperSecureDecoder;
import javax.net.p2p.codec.P2PWrapperSecureEncoder;
import javax.net.p2p.websocket.codec.ByteBufToWebSocketFrameEncoder;
import javax.net.p2p.websocket.codec.WebSocketServerHandshakeMarkHandler;
import javax.net.p2p.websocket.codec.WebSocketFrameToByteBufDecoder;
import javax.net.p2p.websocket.reliability.WebSocketReliabilityHandler;

public class P2PServerWebSocket extends AbstractP2PServer {

    public static final String DEFAULT_PATH = "/p2p";

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public P2PServerWebSocket(Integer port) {
        super(port);
    }

    public P2PServerWebSocket(int port, int queueSize, int coreSize, int magic) {
        super(port, queueSize, coreSize, magic);
    }

    @Override
    public void singletonCreated(Object inatance) {
        if (bossGroup != null || workerGroup != null) {
            throw new RuntimeException("同一服务正在运行，请先关闭后再操作,监听端口->" + port);
        }
        bossGroup = new NioEventLoopGroup();
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.attr(ChannelUtils.MAGIC).set(magic);
                        ch.pipeline()
                            .addLast(new HttpServerCodec())
                            .addLast(new HttpObjectAggregator(1024 * 1024))
                            .addLast(new WebSocketServerProtocolHandler(DEFAULT_PATH, null, false))
                            .addLast(new WebSocketServerHandshakeMarkHandler())
                            .addLast(new WebSocketFrameToByteBufDecoder())
                            .addLast(new P2PWrapperSecureDecoder())
                            .addLast(new WebSocketReliabilityHandler())
                            .addLast(new ServerMessageProcessor(magic, queueSize))
                            .addLast(new ByteBufToWebSocketFrameEncoder())
                            .addLast(new P2PWrapperSecureEncoder());
                    }
                });

            ChannelFuture f = bootstrap.bind(port).sync();
            System.out.println("WebSocket P2P server start listen at " + port);
            f.channel().closeFuture().addListener(cf -> released());
        } catch (Exception e) {
            released();
            throw new RuntimeException(e);
        }
        super.singletonCreated(this);
    }

    @Override
    public void singletonFinalized() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        super.singletonFinalized();
    }
}
