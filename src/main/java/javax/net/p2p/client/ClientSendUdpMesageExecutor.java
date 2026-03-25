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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.channel.PipelineInitializer;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.server.ServerSendTcpMesageExecutor;
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
public class ClientSendUdpMesageExecutor extends ClientSendMesageExecutor implements Runnable {
    
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
            ClientUdpMessageProcessor clientUdpMessageProcessor = new ClientUdpMessageProcessor(messageService, magic, queueSize);
            bootstrap.group(io_work_group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch)
                        throws Exception {
                        PipelineInitializer.initProcessorsUdp(ch, clientUdpMessageProcessor, magic);
                    }
                });
            // 建立连接
            channel = bootstrap.connect(messageService.getRemote()).sync().channel();
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
                        System.out.printf("channel is actice %s,channel is open %s\n", channel.isActive(), channel.isOpen());
                        //notifyClosed();
                    } catch (InterruptedException ex) {
                        connected = false;
                    }
                }
            });
            t.start();
            //提交任务执行本执行器请求队列
            ExecutorServicePool.CLIENT_ASYNC_POOLS.submit(this);
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
        System.out.println(request.getSeq() + " data:" + data.length + " hash:" + hash);

//        ByteBuf buffer = SerializationUtil.serializeToByteBuf(request, magicChannel);
        ByteBuf buffer = SerializationUtil.tryGetDirectBuffer(data.length + 12);
        buffer.writeInt(data.length);
        buffer.writeInt(magicChannel);
        buffer.writeInt(hash);
        buffer.writeBytes(data);
        try {
            if (log.isDebugEnabled()) {
                log.debug("queue size:{}", this.requestQueue.size());
                log.debug("request:{}" + request);
            }
            int length = buffer.readableBytes();
            //分包发送,以防止中间网络路由问题导致传输问题(超时),实测数据域映射端口超过64k tcp包经常超时
            int rest = (int) length % P2PConfig.UDP_TRANSPORT_LIMIT_SIZE;
            int count = (int) length / P2PConfig.UDP_TRANSPORT_LIMIT_SIZE;
            //System.out.println(bytes.length+" -> "+count+" rest "+rest);
            ChannelFuture cf = null;
            int start = buffer.readerIndex();
            for (int i = 0; i < count; i++) {
                buffer.retain();
                //cf = channel.writeAndFlush(buffer.slice(start + i * P2PConfig.UDP_TRANSPORT_LIMIT_SIZE, P2PConfig.UDP_TRANSPORT_LIMIT_SIZE));
                cf = channel.writeAndFlush(new DatagramPacket(buffer.slice(start + i * P2PConfig.UDP_TRANSPORT_LIMIT_SIZE, P2PConfig.UDP_TRANSPORT_LIMIT_SIZE),
                    (InetSocketAddress) channel.remoteAddress()));
                cf.sync();
            }
            if (rest > 0) {
                buffer.retain();
//                cf = channel.writeAndFlush(buffer.slice(start + count * P2PConfig.UDP_TRANSPORT_LIMIT_SIZE, rest));
                cf = channel.writeAndFlush(new DatagramPacket(buffer.slice(start + count * P2PConfig.UDP_TRANSPORT_LIMIT_SIZE, rest),
                    (InetSocketAddress) channel.remoteAddress()));
                cf.sync();
            }
            //System.out.println("queue size:"+this.requestQueue.size());
            //System.out.println("request:"+request);
            //ChannelFuture cf =channel.writeAndFlush(new DatagramPacket(buffer, (InetSocketAddress) channel.remoteAddress()));
            //cf.sync();//udp
        } catch (Exception e) {
            e.printStackTrace();
        }
        Thread.sleep(10000);
        return null;
    }
    
    public final static <T> ClientSendUdpMesageExecutor build(P2PMessageService messageService, int queueSize, InetSocketAddress remote,int magic) {
        ClientSendUdpMesageExecutor t = ConcurrentObjectPool.get(queueSize).poll();
        t.messageService = messageService;
        t.remote = remote;
        t.magic = magic;
//        t.channel = channel;
//        Attribute<Integer> attrMagic = channel.attr(ChannelUtils.MAGIC);
//        t.magic = attrMagic.get();
//        //启动异步任务
//        t.start(channel);
        return t;
    }
    
     @Override
    public void clear(){
        super.clear();
        remote = null;
    }
    
    @Override
    public boolean release() {
        return ConcurrentObjectPool.get(queueSize).offer(this);
    }

    static class ConcurrentObjectPool {

        private static final ThreadLocal<Map<Integer, PooledObjects<ClientSendUdpMesageExecutor>>> LOCAL_POOL = new ThreadLocal<>();

        private static PooledObjects<ClientSendUdpMesageExecutor> get(Integer queueSize) {
            Map<Integer, PooledObjects<ClientSendUdpMesageExecutor>> map = LOCAL_POOL.get();
            PooledObjects<ClientSendUdpMesageExecutor> pool;
            if (map == null) {
                map = new HashMap();
                LOCAL_POOL.set(map);
                pool = new PooledObjects(4096, new PooledObjectFactory<ClientSendUdpMesageExecutor>() {
                    @Override
                    public ClientSendUdpMesageExecutor newInstance() {
                        return new ClientSendUdpMesageExecutor(queueSize);
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
