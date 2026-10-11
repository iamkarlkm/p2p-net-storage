package javax.net.p2p.server.fulltext;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.p2p.channel.PipelineInitializer;
import javax.net.p2p.codec.P2PWrapperEncoder;
import javax.net.p2p.server.AbstractP2PServer;
import javax.net.p2p.server.ServerMessageProcessor;
import javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class FulltextShardTcpServer extends AbstractP2PServer implements AutoCloseable {

    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile Channel channel;
    private final CountDownLatch bindLatch = new CountDownLatch(1);
    private volatile Throwable bindError;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public FulltextShardTcpServer(Integer port) {
        super(port);
    }

    public FulltextShardTcpServer(int port) {
        super(port);
    }

    public FulltextShardTcpServer(int port, int queueSize) {
        super(port, queueSize);
    }

    public FulltextShardTcpServer(int port, int queueSize, int coreSize) {
        super(port, queueSize, coreSize);
    }

    public FulltextShardTcpServer(int port, int queueSize, int coreSize, int magic) {
        super(port, queueSize, coreSize, magic);
    }

    @Override
    public void singletonCreated(Object instance) {
        if (port < 0 || port > 65535) {
            bindError = new IllegalArgumentException("port out of range: " + port);
            bindLatch.countDown();
            return;
        }
        try {
            ServerMessageProcessor.registerProcessors();
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();
            ServerBootstrap server = new ServerBootstrap();
            server.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        PipelineInitializer.initProcessors(ch,
                            new ServerMessageProcessor(magic, queueSize));
                        ch.pipeline().addBefore("BusinessProseccor", "ProtoCodec",
                            new ProtoCodecDuplexHandler());
                    }
                });
            PipelineInitializer.initServerOptions(server);
            ChannelFuture future = server.bind(port).sync();
            channel = future.channel();
            int actual = ((InetSocketAddress) channel.localAddress()).getPort();
            log.info("FulltextShardTcpServer listen at 127.0.0.1:{}", actual);
        } catch (Throwable t) {
            bindError = t;
            shutdownGroupsQuietly();
        } finally {
            bindLatch.countDown();
        }
    }

    @Override
    public void singletonFinalized() {
        close();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (channel != null) {
                channel.close().await(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        } finally {
            shutdownGroupsQuietly();
        }
    }

    private void shutdownGroupsQuietly() {
        if (bossGroup != null) {
            try { bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync(); } catch (Exception ignore) {}
            bossGroup = null;
        }
        if (workerGroup != null) {
            try { workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync(); } catch (Exception ignore) {}
            workerGroup = null;
        }
    }

    public boolean waitForBind(long timeout, TimeUnit unit) throws InterruptedException {
        return bindLatch.await(timeout, unit);
    }

    public int getBoundPort() {
        if (channel == null) {
            try {
                if (!bindLatch.await(10, TimeUnit.SECONDS)) return -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        if (bindError != null) return -1;
        if (channel == null) return -1;
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    public Throwable getBindError() {
        return bindError;
    }

    public boolean isServerAliveForTest() {
        return channel != null && channel.isActive() && bossGroup != null && workerGroup != null;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: FulltextShardTcpServer <port> <shardRoot> <shardCount>");
            System.exit(1);
            return;
        }
        int portArg;
        int shardCountArg;
        try {
            portArg = Integer.parseInt(args[0]);
            shardCountArg = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid numeric arg: " + e.getMessage());
            System.exit(2);
            return;
        }
        if (shardCountArg <= 0) {
            System.err.println("shardCount must be > 0");
            System.exit(3);
            return;
        }
        String shardRoot = args[1];
        String clusterKey = shardRoot + "?sc=" + shardCountArg + "&pfx=svr_shard";
        try {
            DsServerFulltextRegistry.getOrCreate(clusterKey, shardCountArg, "svr_shard");
        } catch (IOException e) {
            System.err.println("Failed to pre-create ShardRouter registry: " + e.getMessage());
            System.exit(4);
            return;
        }
        FulltextShardTcpServer srv = new FulltextShardTcpServer(portArg, 4096, 2, P2PWrapperEncoder.MAGIC);
        Thread bindThread = new Thread(() -> srv.singletonCreated(srv), "FulltextShardTcpServer-bind");
        bindThread.setDaemon(true);
        bindThread.start();
        if (!srv.waitForBind(30, TimeUnit.SECONDS)) {
            System.err.println("Bind timeout");
            System.exit(5);
            return;
        }
        if (srv.getBindError() != null) {
            System.err.println("Bind failed: " + srv.getBindError());
            System.exit(6);
            return;
        }
        int actualPort = srv.getBoundPort();
        System.out.println("FulltextShardTcpServer listen at 127.0.0.1:" + actualPort);
        System.out.flush();
        if (srv.channel != null) {
            try {
                srv.channel.closeFuture().sync();
            } finally {
                srv.close();
            }
        }
    }
}
