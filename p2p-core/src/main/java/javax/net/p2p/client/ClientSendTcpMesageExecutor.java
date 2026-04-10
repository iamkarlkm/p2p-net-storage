
package javax.net.p2p.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.Attribute;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.channel.PipelineInitializer;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.common.pool.PooledObjectFactory;
import javax.net.p2p.common.pool.PooledObjects;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author karl
 */
@Slf4j
public class ClientSendTcpMesageExecutor extends ClientSendMesageExecutor implements Runnable{
    private InetSocketAddress remote;
    
    public ClientSendTcpMesageExecutor(int queueSize) {
        super(queueSize);
    }

//    public ClientSendTcpMesageExecutor(P2PMessageService client, int queueSize,int magic) {
//        super(client, queueSize, magic);
//    }

   
    
    /**
     * 建立连接,不阻塞调用者(启动一个线程,监听连接关闭)
     * @param io_work_group
     * @param bootstrap 
     */
    @Override
    public void connect(EventLoopGroup io_work_group,Bootstrap bootstrap){
        try {
            if(channel!=null){//如果存在旧连接,尝试关闭之,以免半连接泄露
                try{
                    channel.close();
                }catch(Exception e){
                    log.error(e.getMessage());
                }
            }
            ClientTcpMessageProcessor clientTcpMessageProcessor = new ClientTcpMessageProcessor(messageService,magic,queueSize);
            bootstrap.group(io_work_group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch)
                        throws Exception {
                        PipelineInitializer.initProcessors(ch, clientTcpMessageProcessor);
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
                         notifyClosed();
                    } catch (InterruptedException ex) {
                        connected = false;
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
        final ClientSendTcpMesageExecutor other = (ClientSendTcpMesageExecutor) obj;
        return Objects.equals(this.channel, other.channel);
    }

    @Override
    public ChannelFuture writeAndFlush(Channel channel, P2PWrapper request) throws InterruptedException{
        return channel.writeAndFlush(request);
    }
    
    public final static <T> ClientSendTcpMesageExecutor build(P2PMessageService messageService, int queueSize,InetSocketAddress remoteServer, int magic) {
        ClientSendTcpMesageExecutor t = ConcurrentObjectPool.get(queueSize).poll();
        t.messageService = messageService;
        t.queueSize = queueSize;
        t.remote = remoteServer;
        t.magic = magic;
//        Attribute<Integer> attrMagic = channel.attr(ChannelUtils.MAGIC);
//        t.magic = attrMagic.get();
//        //启动异步任务
//        t.start(channel);
        return t;
    }

    @Override
    public void clear() {
        super.clear(); 
        remote = null;
    }
    
    
        
    @Override
    public void recycle() {
        ConcurrentObjectPool.get(queueSize).offer(this);
    }

    static class ConcurrentObjectPool {

        private static final ThreadLocal<Map<Integer, PooledObjects<ClientSendTcpMesageExecutor>>> LOCAL_POOL = new ThreadLocal<>();

        private static PooledObjects<ClientSendTcpMesageExecutor> get(Integer queueSize) {
            Map<Integer, PooledObjects<ClientSendTcpMesageExecutor>> map = LOCAL_POOL.get();
            PooledObjects<ClientSendTcpMesageExecutor> pool;
            if (map == null) {
                map = new HashMap();
                LOCAL_POOL.set(map);
                pool = new PooledObjects(4096, new PooledObjectFactory<ClientSendTcpMesageExecutor>() {
                    @Override
                    public ClientSendTcpMesageExecutor newInstance() {
                        return new ClientSendTcpMesageExecutor(queueSize);
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
