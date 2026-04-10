package javax.net.p2p.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.Attribute;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.common.pool.PooledObjectFactory;
import javax.net.p2p.common.pool.PooledObjects;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;
import javax.net.p2p.utils.XXHashUtil;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author karl
 */
@Slf4j
public class ClientSendQuicMesageExecutor extends ClientSendMesageExecutor {

    private InetSocketAddress remote;
    private QuicStreamChannel streamChannel;

    private ClientSendQuicMesageExecutor(int queueSize) {
        super(queueSize);
    }

    @Override
    public void connect(EventLoopGroup io_work_group, Bootstrap bootstrap) {
        try {
            if (channel != null) {
                try {
                    channel.close();
                } catch (Exception e) {
                    log.error(e.getMessage());
                }
            }

            final EventLoopGroup group = new io.netty.channel.nio.NioEventLoopGroup(1);
            Bootstrap ioBootstrap = new Bootstrap();

            QuicSslContext context = QuicSslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols("http/0.9").build();

            ClientQuicMessageProcessor clientQuicMessageProcessor = new ClientQuicMessageProcessor(messageService, magic, queueSize);

            ChannelHandler codec = new QuicClientCodecBuilder()
                    .sslContext(context)
                    .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                    .initialMaxData(10000000)
                    .initialMaxStreamDataBidirectionalLocal(1000000)
                    .build();

            ioBootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(codec);

            Channel quicDatagramChannel = ioBootstrap.bind(0).sync().channel();

            QuicChannel quicChannel = QuicChannel.newBootstrap(quicDatagramChannel)
                    .streamHandler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            // ignore
                        }
                    })
                    .remoteAddress(messageService.getRemote())
                    .connect()
                    .get();

            streamChannel = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                    new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel ch) {
                            ch.attr(ChannelUtils.MAGIC).set(magic);
                            ch.pipeline().addLast(clientQuicMessageProcessor);
                        }
                    }).sync().getNow();

            channel = streamChannel;
            Attribute<Integer> attrMagic = channel.attr(ChannelUtils.MAGIC);
            attrMagic.set(magic);
            messageService.handleConnectSuccess(channel);
            connected = true;

            Thread t = new Thread(() -> {
                try {
                    channel.closeFuture().sync();
                    quicChannel.closeFuture().sync();
                    quicDatagramChannel.closeFuture().sync();
                    connected = false;
                    group.shutdownGracefully();
                    System.out.printf("channel is actice %s,channel is open %s\n", channel.isActive(), channel.isOpen());
                } catch (InterruptedException ex) {
                    connected = false;
                    group.shutdownGracefully();
                }
            });
            t.start();
            ExecutorServicePool.P2P_REFERENCED_CLIENT_ASYNC_POOLS.submit(this);
        } catch (Exception ex) {
            connected = false;
            messageService.handleConnectFailed(ex);
        }
    }

    public InetSocketAddress getRemote() {
        return remote;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 17 * hash + Objects.hashCode(this.channel);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final ClientSendQuicMesageExecutor other = (ClientSendQuicMesageExecutor) obj;
        return Objects.equals(this.channel, other.channel);
    }

    @Override
    public ChannelFuture writeAndFlush(Channel channel, P2PWrapper request) throws InterruptedException {
        Attribute<Integer> attrMagic = channel.attr(ChannelUtils.MAGIC);
        Integer magicChannel = attrMagic.get();
        if (magicChannel == null) magicChannel = magic;
        byte[] data = SerializationUtil.serialize(request);
        byte[] key = channel.attr(ChannelUtils.XOR_KEY).get();
        if (key != null && key.length > 0 && request.getCommand() != P2PCommand.HAND) {
            javax.net.p2p.auth.utils.AuthCrypto.xorInPlace(data, key);
        }
        //int hash = XXHashUtil.hash32(data);

        ByteBuf buffer = SerializationUtil.tryGetDirectBuffer(data.length + 12);
        buffer.writeInt(data.length);
        buffer.writeInt(magicChannel);
        //buffer.writeInt(hash);
        buffer.writeBytes(data);

        ChannelFuture cf = null;
        try {
            cf = streamChannel.writeAndFlush(buffer);
            cf.sync();
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
        return cf;
    }

    public final static ClientSendQuicMesageExecutor build(P2PMessageService messageService, int queueSize, InetSocketAddress remote, int magic) {
        PooledObjects<ClientSendQuicMesageExecutor> pool = ConcurrentObjectPool.get(queueSize);
        ClientSendQuicMesageExecutor t = pool.poll();
        if (t == null) {
            t = new ClientSendQuicMesageExecutor(queueSize);
        }
        t.messageService = messageService;
        t.remote = remote;
        t.magic = magic;
        return t;
    }

    @Override
    public void recycle() {
        PooledObjects<ClientSendQuicMesageExecutor> pool = ConcurrentObjectPool.get(queueSize);
        if (pool != null) {
            pool.offer(this);
        }
    }

    static class ConcurrentObjectPool {
        private static final ThreadLocal<Map<Integer, PooledObjects<ClientSendQuicMesageExecutor>>> LOCAL_POOL = new ThreadLocal<>();

        private static PooledObjects<ClientSendQuicMesageExecutor> get(final Integer queueSize) {
            Map<Integer, PooledObjects<ClientSendQuicMesageExecutor>> map = LOCAL_POOL.get();
            if (map == null) {
                map = new HashMap<>();
                LOCAL_POOL.set(map);
            }

            PooledObjects<ClientSendQuicMesageExecutor> pool = map.get(queueSize);
            if (pool == null) {
                pool = new PooledObjects<>(4096, new PooledObjectFactory<ClientSendQuicMesageExecutor>() {
                    @Override
                    public ClientSendQuicMesageExecutor newInstance() {
                        return new ClientSendQuicMesageExecutor(queueSize);
                    }
                });
                map.put(queueSize, pool);
            }
            return pool;
        }
    }
}
