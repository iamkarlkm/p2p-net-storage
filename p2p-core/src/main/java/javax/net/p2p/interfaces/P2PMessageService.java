package javax.net.p2p.interfaces;

import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.p2p.channel.AbstractStreamResponseAdapter;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.common.ChannelAwaitOnMessage;
import javax.net.p2p.model.P2PWrapper;

/**
 * 消息处理通用接口
 * @author karl
 */

public interface P2PMessageService  {

 
    P2PWrapper excute(P2PWrapper request) throws Exception;
    
    void cancelExcute(int requestId) ;

    Future<P2PWrapper> asyncExcute(P2PWrapper request) throws Exception;
    
   

    P2PWrapper excute(P2PWrapper request, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException ;

    Future<P2PWrapper> asyncExcute(P2PWrapper request, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException ;
    
    /**
     * (文件下载/订阅)流消息返回,异步回调
     * @param request
     * @param streamMessage 异步回调
     * @return
     * @throws Exception 
     */
//    @Override
    P2PWrapper streamRequest(P2PWrapper request,AbstractStreamResponseAdapter streamMessage) throws Exception ;

    Future<P2PWrapper> asyncStreamRequest(P2PWrapper request) throws Exception ;

    P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request) throws InterruptedException, ExecutionException, TimeoutException;
   

    P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException;


    /**
     * 从已有业务处理器集或对应连接池获取一个空闲业务处理器
     *
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    AbstractSendMesageExecutor pollMesageExecutor(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException ;

    InetSocketAddress getRemote();

    ConcurrentMap<Integer, ChannelAwaitOnMessage<P2PWrapper>> getResponseFutureMap();

    void putResponseFuture(int requestId, ChannelAwaitOnMessage<P2PWrapper> task);
    
    void removeResponseFuture(int requestId);

    ChannelAwaitOnMessage pollChannelAwaitOnMessage(P2PWrapper request, long timeout, TimeUnit unit);
    

    P2PWrapper exception(P2PWrapper request, Exception e) ;

    ChannelAwaitOnMessage<P2PWrapper> removeWithException(P2PWrapper request,Exception e) ;

    ChannelAwaitOnMessage<P2PWrapper> getChannelAwaitOnMessage(P2PWrapper request) ;

    void completeExceptionally(P2PWrapper request, Exception e);

    void complete( P2PWrapper response);
    

    /**
     * 获取当前总连接数
     *
     * @return
     */
    int getTotalConnects();
    
    public void handleConnectSuccess(Channel channel);

    public void handleConnectFailed(Exception ex);

    public void handleMesageExecutorQueueEmpty(AbstractSendMesageExecutor executor);
    
    public void handleMesageExecutorClose(AbstractSendMesageExecutor executor);
    
    
}
