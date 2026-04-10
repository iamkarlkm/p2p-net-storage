package javax.net.p2p.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import javax.net.p2p.channel.AbstractUdpMessageProcessor;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;
/**
 * ClientUdpMessageProcessor。
 */

@Slf4j
public class ClientUdpMessageProcessor extends AbstractUdpMessageProcessor {
    
    /**
     * 每一个服务器对应一个responseFutureMap以便连接到同一服务器的多个客户端连接可以共用
     */
    //private static final ConcurrentMap<InetSocketAddress, ConcurrentMap<Integer, ChannelAwaitOnMessage<P2PWrapper>>> SERVER_RESPONSE_FUTURE_MAP = new ConcurrentHashMap<>(4096);

    //private final ConcurrentMap<Integer, ChannelAwaitOnMessage<P2PWrapper>> responseFutureMap;

    
    //private final ArrayBlockingQueue<ChannelAwaitOnMessage<P2PWrapper>> AWAIT_ON_MESSAGE_LOCKS;
    //private final PooledObjects<ChannelAwaitOnMessage<P2PWrapper>> AWAIT_ON_MESSAGE_LOCK_POOL;

//    private int timeoutCount = 0;
//
//    private int timeOutCountLimit = 6;
    
    


    /**
     * 等待服务器消息异步锁
     * @param client
     * @param magic
     * @param queueSize
     */
    public ClientUdpMessageProcessor(P2PMessageService client,int magic,int queueSize) {
       super(client,magic,queueSize);
        //每一个服务器对应一个responseFutureMap以便连接到同一服务器的多个客户端连接可以共用(请求序列号一一对应)
//        ConcurrentMap<Integer, ChannelAwaitOnMessage<P2PWrapper>> map = SERVER_RESPONSE_FUTURE_MAP.get(client.getRemoteServer());
//        if(map==null){
//            map = new ConcurrentHashMap<>(queueSize);
//            SERVER_RESPONSE_FUTURE_MAP.put(client.getRemoteServer(), map);
//        }
//        this.responseFutureMap = map;
        //this.AWAIT_ON_MESSAGE_LOCKS = new ArrayBlockingQueue(queueSize);
        //AWAIT_ON_MESSAGE_LOCK_POOL = new PooledObjects<>(ChannelAwaitOnMessage.class,queueSize);

    }

    

    /**
     * 处理服务器回应消息
     *
     * @param ctx
     * @param datagramPacket
     * @param response
     */
    @Override
    public void processMessage(ChannelHandlerContext ctx, DatagramPacket datagramPacket, P2PWrapper response) {
        messageService.complete(response);
    }

   

}
