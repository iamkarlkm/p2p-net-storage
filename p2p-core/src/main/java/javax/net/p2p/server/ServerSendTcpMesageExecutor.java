package javax.net.p2p.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.util.Attribute;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.common.pool.PooledObjectFactory;
import javax.net.p2p.common.pool.PooledObjects;
import javax.net.p2p.interfaces.P2PMessageService;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author karl
 */
@Slf4j
public class ServerSendTcpMesageExecutor extends AbstractSendMesageExecutor {

//    private InetSocketAddress remote;//udp 対端地址可能变动,非tcp一一对应,发送消息前校验

    
//    protected final Map<Integer, ByteBuf> lastMessageMap = new ConcurrentHashMap<>();
//    //最近发送消息缓存
//    protected final Map<Integer, Map<Integer, ByteBuf>> lastMessageSegmentsMap = new ConcurrentHashMap<>();
    protected ServerSendTcpMesageExecutor(int queueSize) {
        super(queueSize);
        
    }

   
    /**
     *
     * @param obj
     * @return
     */
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
        final ServerSendTcpMesageExecutor other = (ServerSendTcpMesageExecutor) obj;
        return Objects.equals(this.channel, other.channel);
    }



    public final static <T> ServerSendTcpMesageExecutor build(P2PMessageService messageService, int queueSize, Channel channel) {
        ServerSendTcpMesageExecutor t = ConcurrentObjectPool.get(queueSize).poll();
        t.messageService = messageService;
        t.channel = channel;
        Attribute<Integer> attrMagic = channel.attr(ChannelUtils.MAGIC);
        t.magic = attrMagic.get();
        //启动异步任务
        t.start(channel);
        return t;
    }
    
    @Override
    public void connect(EventLoopGroup io_work_group, Bootstrap bootstrap) {
        //TODO
        //server
    }

    @Override
    public boolean release() {
        return ConcurrentObjectPool.get(queueSize).offer(this);
    }

    static class ConcurrentObjectPool {

        private static final ThreadLocal<Map<Integer, PooledObjects<ServerSendTcpMesageExecutor>>> LOCAL_POOL = new ThreadLocal<>();

        private static PooledObjects<ServerSendTcpMesageExecutor> get(Integer queueSize) {
            Map<Integer, PooledObjects<ServerSendTcpMesageExecutor>> map = LOCAL_POOL.get();
            PooledObjects<ServerSendTcpMesageExecutor> pool;
            if (map == null) {
                map = new HashMap();
                LOCAL_POOL.set(map);
                pool = new PooledObjects(4096, new PooledObjectFactory<ServerSendTcpMesageExecutor>() {
                    @Override
                    public ServerSendTcpMesageExecutor newInstance() {
                        return new ServerSendTcpMesageExecutor(queueSize);
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
