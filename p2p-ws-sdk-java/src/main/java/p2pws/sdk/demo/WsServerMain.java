package p2pws.sdk.demo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.nio.file.Path;
import p2pws.sdk.FileKeyFileProvider;

public final class WsServerMain {

    public static void main(String[] args) throws Exception {
        Path cfgPath = args.length >= 1 ? Path.of(args[0]) : Path.of("..", "p2p-ws-protocol", "examples", "server.yaml").normalize();
        ServerConfig cfg = YamlConfig.loadServer(cfgPath);
        Path keyfilePath = Path.of(cfg.keyfilePath()).toAbsolutePath().normalize();
        System.out.println("config.keyfile_path=" + cfg.keyfilePath());
        byte[] keyId = FileKeyFileProvider.sha256(keyfilePath);

        FileKeyFileProvider provider = new FileKeyFileProvider();
        provider.put(keyfilePath);

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                            .addLast(new HttpServerCodec())
                            .addLast(new HttpObjectAggregator(1024 * 1024))
                            .addLast(new WebSocketServerProtocolHandler(cfg.wsPath(), null, false))
                            .addLast(new DemoServerHandler(provider, keyId, provider.length(keyId), cfg.magic(), cfg.version(), cfg.flagsPlain(), cfg.flagsEncrypted(), cfg.maxFramePayload()));
                    }
                });
            b.bind(cfg.listenPort()).sync();
            System.out.println("ws server listen=" + cfg.listenPort());
            System.out.println("keyId.sha256.hex=" + Hex.hex(keyId));
            System.out.println("keyfile.path=" + keyfilePath);
            Thread.currentThread().join();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }
}
