package javax.net.p2p.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.Attribute;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.channel.PipelineInitializer;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.common.pool.PooledObjectFactory;
import javax.net.p2p.common.pool.PooledObjects;
import javax.net.p2p.config.P2PConfig;
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
public class ClientSendUdpMesageExecutor extends ClientSendMesageExecutor {
    
    private InetSocketAddress remote;//udp 対端地址可能变动,非tcp一一对应,发送消息前校验

    private ClientSendUdpMesageExecutor(int queueSize) {
        super(queueSize);
    }
    
//    private ClientSendUdpMesageExecutor(P2PMessageService client, int queueSize, int magic) {
//        super(client, queueSize, magic);
//    }

    /**
     * 建立连接,不阻塞调用者(启动一个线程,监听连接关闭)
     *
     * @param io_work_group
     * @param bootstrap
     */
    @Override
    public void connect(EventLoopGroup io_work_group, Bootstrap bootstrap) {
        try {
            if (channel != null) {//如果存在旧连接,尝试关闭之,以免半连接泄露
                try {
                    channel.close();
                } catch (Exception e) {
                    log.error(e.getMessage());
                }
            }
            
            // 每次连接创建新的 EventLoopGroup 和 Bootstrap，避免 group set already 异常
            final EventLoopGroup group = new io.netty.channel.nio.NioEventLoopGroup(1);
            Bootstrap ioBootstrap = new Bootstrap();
            
            ClientUdpMessageProcessor clientUdpMessageProcessor = new ClientUdpMessageProcessor(messageService, magic, queueSize);
            ioBootstrap.group(group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch)
                        throws Exception {
                        PipelineInitializer.initProcessorsUdpWithReliability(ch, clientUdpMessageProcessor, magic,true);
                    }
                });
            PipelineInitializer.initClientOptionsUdp(ioBootstrap);
                
            // 建立连接
            channel = ioBootstrap.connect(messageService.getRemote()).sync().channel();
            //初始化必要的连接属性
            Attribute<Integer> attrMagic = channel.attr(ChannelUtils.MAGIC);
            attrMagic.set(magic);
            messageService.handleConnectSuccess(channel);
            connected = true;
            //启动一个线程,监听连接关闭
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        //等待->直到连接关闭
                        channel.closeFuture().sync();
                        connected = false;
                        group.shutdownGracefully();
                        System.out.printf("channel is actice %s,channel is open %s\n", channel.isActive(), channel.isOpen());
                        //notifyClosed();
                    } catch (InterruptedException ex) {
                        connected = false;
                        group.shutdownGracefully();
                    }
                }
            });
            t.start();
            //提交任务执行本执行器请求队列
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
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final ClientSendUdpMesageExecutor other = (ClientSendUdpMesageExecutor) obj;
        return Objects.equals(this.channel, other.channel);
    }

    @Override
    public ChannelFuture writeAndFlush(Channel channel, P2PWrapper request) throws InterruptedException {
        Attribute<Integer> attrMagic = channel.attr(ChannelUtils.MAGIC);
        Integer magicChannel = attrMagic.get();
        byte[] data = SerializationUtil.serialize(request);
        int hash = XXHashUtil.hash32(data);
        //System.out.println(request.getSeq() + " data:" + data.length + " hash:" + hash);

//        ByteBuf buffer = SerializationUtil.serializeToByteBuf(request, magicChannel);
        ByteBuf buffer = SerializationUtil.tryGetDirectBuffer(data.length + 12);
        buffer.writeInt(data.length);
        buffer.writeInt(magicChannel);
        buffer.writeInt(hash);//写入哈希种子
        buffer.writeBytes(data);
         ChannelFuture cf = null;
        try {
            if (log.isDebugEnabled()) {
                log.debug("queue size:{}", this.requestQueue.size());
                log.debug("request:{}" + request);
            }
            int length = buffer.readableBytes();
            
            // 优化UDP分片逻辑，考虑MTU限制（通常1500字节，减去IP和UDP头约1472字节）
            // 使用更小的分片大小，避免IP分片
            int maxUdpPayloadSize = 1472; // 考虑IP和UDP头的MTU限制
            int udpLimit = Math.min(P2PConfig.UDP_TRANSPORT_LIMIT_SIZE, maxUdpPayloadSize);
            
            // 分包发送，避免UDP数据包过大导致IP分片
            int rest = length % udpLimit;
            int count = length / udpLimit;
            
            int start = buffer.readerIndex();
            List<ByteBuf> slices = new ArrayList<>();
            
            try {
                // 先创建所有分片
                for (int i = 0; i < count; i++) {
                    ByteBuf slice = buffer.slice(start + i * udpLimit, udpLimit);
                    slice.retain(); // 保留引用
                    slices.add(slice);
                }
                if (rest > 0) {
                    ByteBuf slice = buffer.slice(start + count * udpLimit, rest);
                    slice.retain(); // 保留引用
                    slices.add(slice);
                }
                
                // 发送所有分片
                for (ByteBuf slice : slices) {
                    try {
                        cf = channel.writeAndFlush(new DatagramPacket(slice, (InetSocketAddress) channel.remoteAddress()));
                        cf.sync();
                    } finally {
                        slice.release(); // 确保释放
                    }
                }
            } finally {
                // 确保所有分片都被释放
                for (ByteBuf slice : slices) {
                    if (slice.refCnt() > 0) {
                        slice.release();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
        // Thread.sleep(10000); // 移除阻塞等待，UDP不需要长时间等待
        // 对于需要可靠性的UDP消息，应该有专门的确认机制，而不是简单的sleep
        //TODO
        return cf;
    }
    
    public final static ClientSendUdpMesageExecutor build(P2PMessageService messageService, int queueSize, InetSocketAddress remote, int magic) {
        // 使用 ConcurrentObjectPool 获取实例
        PooledObjects<ClientSendUdpMesageExecutor> pool = ConcurrentObjectPool.get(queueSize);
        ClientSendUdpMesageExecutor t = pool.poll();
        if (t == null) {
            // 如果池为空，则创建新实例
            t = new ClientSendUdpMesageExecutor(queueSize);
        }
        t.messageService = messageService;
        t.remote = remote;
        t.magic = magic;
        return t;
    }
    
    @Override
    public boolean release() {
        // 将对象归还给池
        PooledObjects<ClientSendUdpMesageExecutor> pool = ConcurrentObjectPool.get(queueSize);
        if (pool != null) {
            return pool.offer(this);
        }
        return false;
    }

    static class ConcurrentObjectPool {

        // ThreadLocal 用于每个线程维护自己的对象池，避免锁竞争
        private static final ThreadLocal<Map<Integer, PooledObjects<ClientSendUdpMesageExecutor>>> LOCAL_POOL = new ThreadLocal<>();

        private static PooledObjects<ClientSendUdpMesageExecutor> get(final Integer queueSize) {
            Map<Integer, PooledObjects<ClientSendUdpMesageExecutor>> map = LOCAL_POOL.get();
            if (map == null) {
                map = new HashMap<>();
                LOCAL_POOL.set(map);
            }
            
            PooledObjects<ClientSendUdpMesageExecutor> pool = map.get(queueSize);
            if (pool == null) {
                pool = new PooledObjects<>(4096, new PooledObjectFactory<ClientSendUdpMesageExecutor>() {
                    @Override
                    public ClientSendUdpMesageExecutor newInstance() {
                        return new ClientSendUdpMesageExecutor(queueSize);
                    }
                });
                map.put(queueSize, pool);
            }
            return pool;
        }

    }


}
