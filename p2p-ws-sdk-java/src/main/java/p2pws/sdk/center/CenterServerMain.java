package p2pws.sdk.center;

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
import java.util.concurrent.TimeUnit;
import p2pws.sdk.FileKeyFileProvider;

public final class CenterServerMain {

    public static void main(String[] args) throws Exception {
        Path cfgPath = args.length >= 1 ? Path.of(args[0]) : Path.of("..", "p2p-ws-protocol", "examples", "center.yaml").normalize();
        CenterConfig cfg = CenterYaml.loadCenter(cfgPath);

        Path keyfilePath = Path.of(cfg.keyfilePath()).toAbsolutePath().normalize();
        byte[] keyId = FileKeyFileProvider.sha256(keyfilePath);

        FileKeyFileProvider provider = new FileKeyFileProvider();
        provider.put(keyfilePath);

        RegisteredUsers users = CenterYaml.loadRegisteredUsers(Path.of(cfg.registeredUsersPath()));
        PresenceStore presence = new PresenceStore();

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
                            .addLast(new CenterServerHandler(provider, keyId, provider.length(keyId), cfg.magic(), cfg.version(), cfg.flagsPlain(), cfg.flagsEncrypted(), cfg.maxFramePayload(), cfg.ttlSeconds(), users, presence));
                    }
                });
            b.bind(cfg.listenPort()).sync();
            System.out.println("center listen=" + cfg.listenPort());
            System.out.println("keyId.sha256.hex=" + p2pws.sdk.demo.Hex.hex(keyId));
            worker.next().scheduleAtFixedRate(() -> {
                int n = presence.purgeExpired(System.currentTimeMillis());
                if (n > 0) {
                    System.out.println("presence purged=" + n);
                }
            }, cfg.ttlSeconds(), cfg.ttlSeconds(), TimeUnit.SECONDS);
            Thread.currentThread().join();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }
}
