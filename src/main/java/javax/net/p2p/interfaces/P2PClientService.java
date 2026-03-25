package javax.net.p2p.interfaces;

import io.netty.channel.Channel;
import io.netty.channel.pool.SimpleChannelPool;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.p2p.channel.AbstractStreamResponseAdapter;
import javax.net.p2p.client.ClientSendMesageExecutor;
import javax.net.p2p.common.ChannelAwaitOnMessage;
import javax.net.p2p.model.P2PWrapper;

public interface P2PClientService {

    public SimpleChannelPool getChannelPool();

    /**
     * 获取远程服务器地址
     * @return 
     */
    public InetSocketAddress getRemote();

    /**
     * 实现接口的核心方法
     *
     * @param request
     * @return
     * @throws java.lang.Exception
     */
    public P2PWrapper excute(final P2PWrapper request) throws Exception;

    /**
     * 取消指定请求序列号的网络长时间操作
     *
     * @param requestId
     */
    public void cancelExcute(int requestId);

    /**
     * 请求异步执行
     *
     * @param request
     * @return
     */
    public Future<P2PWrapper> asyncExcute(final P2PWrapper request) throws Exception;
    
    public P2PWrapper excute(P2PWrapper request, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException;
    
    public Future<P2PWrapper> asyncExcute(P2PWrapper request, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException;
    
    /**
     * (文件下载/订阅)流消息返回,异步回调
     * @param request
     * @param streamMessage 异步回调
     * @return
     * @throws Exception 
     */
    public P2PWrapper streamRequest(P2PWrapper request,AbstractStreamResponseAdapter streamMessage) throws Exception;
    
    public Future<P2PWrapper> asyncStreamRequest(P2PWrapper request) throws Exception;
    
    public P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException;
    
    /**
     * 
     * @param request
     * @return 
     */
    public ChannelAwaitOnMessage<P2PWrapper> getChannelAwaitOnMessage(P2PWrapper request);

    public P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request) throws InterruptedException, ExecutionException, TimeoutException;

    public void complete(P2PWrapper response);

    public void completeExceptionally(P2PWrapper request, Exception ex);

    public void handleMesageExecutorClose(ClientSendMesageExecutor aThis);

    public void handleMesageExecutorQueueEmpty(ClientSendMesageExecutor aThis);

    public ChannelAwaitOnMessage pollChannelAwaitOnMessage(P2PWrapper request, long timeout, TimeUnit unit);

    public void putResponseFuture(int requestId, ChannelAwaitOnMessage<P2PWrapper> task);

    public void removeResponseFuture(int requestId);

    public ChannelAwaitOnMessage<P2PWrapper> removeWithException(P2PWrapper request, Exception e);

    public P2PWrapper exception(P2PWrapper request, Exception ex);

    public void handleConnectFailed(Exception ex);

    public void handleConnectSuccess(Channel channel);

}
