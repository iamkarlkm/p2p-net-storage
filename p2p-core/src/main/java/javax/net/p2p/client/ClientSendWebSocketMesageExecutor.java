package javax.net.p2p.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.util.Attribute;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.channel.PipelineInitializer;
import javax.net.p2p.codec.P2PWrapperSecureDecoder;
import javax.net.p2p.codec.P2PWrapperSecureEncoder;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.common.pool.PooledObjectFactory;
import javax.net.p2p.common.pool.PooledObjects;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.P2PServerWebSocket;
import javax.net.p2p.websocket.codec.ByteBufToWebSocketFrameEncoder;
import javax.net.p2p.websocket.codec.WebSocketClientHandshakeAwaitHandler;
import javax.net.p2p.websocket.codec.WebSocketFrameToByteBufDecoder;
import javax.net.p2p.websocket.reliability.WebSocketReliabilityHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ClientSendWebSocketMesageExecutor extends ClientSendMesageExecutor {

    private InetSocketAddress remote;
    private String path;
    private String sessionId;
    private EventLoopGroup workGroup;

    private ClientSendWebSocketMesageExecutor(int queueSize) {
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
            if (workGroup != null) {
                try {
                    workGroup.shutdownGracefully();
                } catch (Exception e) {
                }
                workGroup = null;
            }
            workGroup = new NioEventLoopGroup(1);

            String p = (path == null || path.isBlank()) ? P2PServerWebSocket.DEFAULT_PATH : path;
            URI uri = new URI("ws", null, remote.getHostString(), remote.getPort(), p, null, null);
            WebSocketClientProtocolHandler wsHandler = new WebSocketClientProtocolHandler(
                WebSocketClientHandshakerFactory.newHandshaker(uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders()),
                true
            );
            final Promise<Void>[] handshake = new Promise[1];

            ClientTcpMessageProcessor clientProcessor = new ClientTcpMessageProcessor(messageService, magic, queueSize);

            Bootstrap bs = new Bootstrap();
            PipelineInitializer.initClientOptions(bs);
            bs.group(workGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.attr(ChannelUtils.MAGIC).set(magic);
                        if (sessionId != null && !sessionId.isBlank()) {
                            ch.attr(WebSocketReliabilityHandler.SESSION_ID).set(sessionId);
                        }
                        handshake[0] = ch.eventLoop().newPromise();
                        ch.pipeline()
                            .addLast(new HttpClientCodec())
                            .addLast(new HttpObjectAggregator(1024 * 1024))
                            .addLast(wsHandler)
                            .addLast(new WebSocketClientHandshakeAwaitHandler(handshake[0]))
                            .addLast(new WebSocketFrameToByteBufDecoder())
                            .addLast(new P2PWrapperSecureDecoder())
                            .addLast(new WebSocketReliabilityHandler(sessionId))
                            .addLast(clientProcessor)
                            .addLast(new ByteBufToWebSocketFrameEncoder())
                            .addLast(new P2PWrapperSecureEncoder());
                    }
                });

            channel = bs.connect(remote).sync().channel();
            if (handshake[0] != null) {
                handshake[0].sync();
            }

            Attribute<Integer> attrMagic = channel.attr(ChannelUtils.MAGIC);
            attrMagic.set(magic);
            messageService.handleConnectSuccess(channel);
            connected = true;

            Thread t = new Thread(() -> {
                try {
                    channel.closeFuture().sync();
                    connected = false;
                    notifyClosed();
                } catch (InterruptedException ex) {
                    connected = false;
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            ExecutorServicePool.P2P_REFERENCED_CLIENT_ASYNC_POOLS.submit(this);
        } catch (Exception ex) {
            connected = false;
            messageService.handleConnectFailed(ex);
        }
    }

    @Override
    public ChannelFuture writeAndFlush(Channel channel, P2PWrapper request) {
        return channel.writeAndFlush(request);
    }

    public static ClientSendWebSocketMesageExecutor build(P2PMessageService messageService, int queueSize, InetSocketAddress remoteServer, int magic, String path, String sessionId) {
        ClientSendWebSocketMesageExecutor t = ConcurrentObjectPool.get(queueSize).poll();
        if (t == null) {
            t = new ClientSendWebSocketMesageExecutor(queueSize);
        }
        t.messageService = messageService;
        t.queueSize = queueSize;
        t.remote = remoteServer;
        t.magic = magic;
        t.path = path;
        t.sessionId = sessionId;
        return t;
    }

    @Override
    public void clear() {
        super.clear();
        remote = null;
        path = null;
        sessionId = null;
        if (workGroup != null) {
            try {
                workGroup.shutdownGracefully();
            } catch (Exception e) {
            }
            workGroup = null;
        }
    }

    @Override
    public void recycle() {
        ConcurrentObjectPool.get(queueSize).offer(this);
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
        final ClientSendWebSocketMesageExecutor other = (ClientSendWebSocketMesageExecutor) obj;
        return Objects.equals(this.channel, other.channel);
    }

    static class ConcurrentObjectPool {
        private static final ThreadLocal<Map<Integer, PooledObjects<ClientSendWebSocketMesageExecutor>>> LOCAL_POOL = new ThreadLocal<>();

        private static PooledObjects<ClientSendWebSocketMesageExecutor> get(Integer queueSize) {
            Map<Integer, PooledObjects<ClientSendWebSocketMesageExecutor>> map = LOCAL_POOL.get();
            PooledObjects<ClientSendWebSocketMesageExecutor> pool;
            if (map == null) {
                map = new HashMap<>();
                LOCAL_POOL.set(map);
                pool = new PooledObjects<>(4096, new PooledObjectFactory<ClientSendWebSocketMesageExecutor>() {
                    @Override
                    public ClientSendWebSocketMesageExecutor newInstance() {
                        return new ClientSendWebSocketMesageExecutor(queueSize);
                    }
                });
                map.put(queueSize, pool);
            } else {
                pool = map.get(queueSize);
            }
            return pool;
        }
    }
}
