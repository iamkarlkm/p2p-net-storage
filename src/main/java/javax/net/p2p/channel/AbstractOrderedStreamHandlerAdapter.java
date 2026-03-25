
package javax.net.p2p.channel;

import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.net.p2p.common.ChannelAwaitOnMessage;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.server.ServerSendUdpMesageExecutor;

/**
 * 流消息处理基类
 * @author karl
 */
public abstract class AbstractOrderedStreamHandlerAdapter implements P2PCommandHandler {

    /**
     * 存储channel对应的流消息序列号对应的当前window最小index(已发送)
     */
    private static final ConcurrentMap<Channel, ConcurrentMap<Integer, Integer>> SERVER_RESPONSE_STREAM_WINDOW_INDEX_MAP = new ConcurrentHashMap<>(4096);

    /**
     * 存储channel对应的流消息序列号对应的当前未发送的消息集合
     */
    private static final ConcurrentMap<Channel, ConcurrentMap<Integer, ArrayList<StreamP2PWrapper>>> SERVER_RESPONSE_STREAM_CACHE = new ConcurrentHashMap<>(4096);
    
      /**
     * 等待服务器消息异步锁
     */
    private final ArrayBlockingQueue<ChannelAwaitOnMessage<P2PWrapper>> AWAIT_ON_MESSAGE_LOCKS = new ArrayBlockingQueue(4096);

    
    public void asyncProcess(ServerSendUdpMesageExecutor executor,AbstractStreamRequestAdapter handler,P2PWrapper request){
        try {
//            P2PWrapper response = handler.process(request);
//            executor.sendResponse(response);
//异步等待服务器回应,线程被中断表示本次交互已完成,返回结果
            //Thread.sleep(timeout);
//            ChannelAwaitOnMessage<P2PWrapper> f = AWAIT_ON_MESSAGE_LOCKS.peek();
//            if (f == null) {
//                f = new ChannelAwaitOnMessage();
//                AWAIT_ON_MESSAGE_LOCKS.offer(f);
//            } 
            processStream(executor, request);
        } catch (Exception ex) {
            System.getLogger(AbstractOrderedStreamHandlerAdapter.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
    }
    
    @Override
    public P2PWrapper process(P2PWrapper request){
        
        throw new RuntimeException(new IllegalAccessException());
        
    }
    
    public abstract void processStream(ServerSendUdpMesageExecutor executor,P2PWrapper request);

}
