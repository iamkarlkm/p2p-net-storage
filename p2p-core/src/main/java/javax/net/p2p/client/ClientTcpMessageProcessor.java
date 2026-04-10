package javax.net.p2p.client;

import io.netty.channel.ChannelHandlerContext;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractTcpMessageProcessor;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;
/**
 * ClientTcpMessageProcessor。
 */

@Slf4j
public class ClientTcpMessageProcessor extends AbstractTcpMessageProcessor {
    
    /**
     * 每一个服务器对应一个responseFutureMap以便连接到同一服务器的多个客户端连接可以共用
     */
//    private static final ConcurrentMap<InetSocketAddress, ConcurrentMap<Integer, ChannelAwaitOnMessage<P2PWrapper>>> SERVER_RESPONSE_FUTURE_MAP = new ConcurrentHashMap<>(4096);
//
//    private final ConcurrentMap<Integer, ChannelAwaitOnMessage<P2PWrapper>> responseFutureMap;
//
//    /**
//     * 等待服务器消息异步锁
//     */
//    private final ArrayBlockingQueue<ChannelAwaitOnMessage<P2PWrapper>> AWAIT_ON_MESSAGE_LOCKS;
//
//    private int timeoutCount = 0;
//
//    private int timeOutCountLimit = 6;
    
    protected P2PMessageService client;


    public ClientTcpMessageProcessor(P2PMessageService client,int magic,int queueSize) {
        super(magic,queueSize);
        this.client = client;
        //每一个服务器对应一个responseFutureMap以便连接到同一服务器的多个客户端连接可以共用(请求序列号一一对应)
//        ConcurrentMap<Integer, ChannelAwaitOnMessage<P2PWrapper>> map = SERVER_RESPONSE_FUTURE_MAP.get(client.getRemoteServer());
//        if(map==null){
//            map = new ConcurrentHashMap<>(queueSize);
//            SERVER_RESPONSE_FUTURE_MAP.put(client.getRemoteServer(), map);
//        }
//        this.responseFutureMap = map;
//        this.AWAIT_ON_MESSAGE_LOCKS = new ArrayBlockingQueue(queueSize);

    }

  
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, P2PWrapper response) throws Exception {
        if(log.isDebugEnabled()){
            log.debug("channel {} read msg ->\n{}", ctx.channel().id(), response);
        }
        
        client.complete(response);
    }

}
