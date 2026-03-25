package javax.net.p2p.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.AbstractChannelPoolMap;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.pool.SimpleChannelPool;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.Closeable;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.p2p.channel.PipelineInitializer;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author karl
 */
@Slf4j
public class NettyPoolClient implements Closeable {

    private EventLoopGroup worker;

    private Bootstrap bootstrap;

    //private static volatile NettyPoolClient CLIENT;
    private AbstractChannelPoolMap<InetSocketAddress, SimpleChannelPool> channelPoolMap;

    private static final AtomicInteger RESET_COUNT = new AtomicInteger();

    private P2PClient client;

    public P2PClient getClient() {
        return client;
    }

    public void setClient(P2PClient client) {
        this.client = client;
    }

    private NettyPoolClient(P2PClient client) {
        worker = new NioEventLoopGroup();
        bootstrap = new Bootstrap();
        this.client = client;
    }

    public static NettyPoolClient getInstance(P2PClient client) {
        return new NettyPoolClient(client).init();
    }

    private NettyPoolClient init() {
        try {
            bootstrap.group(worker).channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            //ChannelPipeline pipeline = ch.pipeline();
                            //pipeline.addLast(new ClientUdpMessageProcessor(client));
                            PipelineInitializer.initProcessors(channel, new ClientMessageProcessor(client));
                        }
                    });
            PipelineInitializer.initClientOptions(bootstrap);

            channelPoolMap = new AbstractChannelPoolMap<InetSocketAddress, SimpleChannelPool>() {
                @Override
                protected SimpleChannelPool newPool(InetSocketAddress key) {
                    return new FixedChannelPool(bootstrap.remoteAddress(key), new NettyChannelPoolHandler(client),
                        ChannelHealthChecker.ACTIVE, FixedChannelPool.AcquireTimeoutAction.FAIL, 90000, Runtime.getRuntime().availableProcessors() * 2, 4096);
                }
            };

        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return this;

    }

    public void reset() {
        try ( //		if (CLIENT != null) {
            //			synchronized (NettyPoolClient.class) {
            //				if (CLIENT != null) {
            //					try {
            //						CLIENT.close();
            //					} catch (Exception e) {
            //						e.printStackTrace();
            //					}
            //				}
            //			}
            //		}
            AbstractChannelPoolMap<InetSocketAddress, SimpleChannelPool> channelPoolMap0 = channelPoolMap) {
            channelPoolMap = new AbstractChannelPoolMap<InetSocketAddress, SimpleChannelPool>() {
                @Override
                protected SimpleChannelPool newPool(InetSocketAddress key) {
                    return new FixedChannelPool(bootstrap.remoteAddress(key), new NettyChannelPoolHandler(client),
                        ChannelHealthChecker.ACTIVE, FixedChannelPool.AcquireTimeoutAction.FAIL, 90000, Runtime.getRuntime().availableProcessors(), 4096);
                }
            };
            for (Map.Entry<InetSocketAddress, SimpleChannelPool> entry : channelPoolMap0) {

                try {
                    entry.getValue().close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        log.warn("NettyPoolClient客户端重置成功{}次...", RESET_COUNT.incrementAndGet());
    }

    @Override
    public void close() {

        worker.shutdownGracefully();
        worker = null;
        bootstrap = null;
        channelPoolMap.close();
        channelPoolMap = null;

    }

    public SimpleChannelPool getChannelPool(InetSocketAddress remoteServer) {
        return channelPoolMap.get(remoteServer);
    }

}
