
package javax.net.p2p.client.pooled;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.pool.SimpleChannelPool;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.p2p.common.ChannelAwaitOnMessage;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.exception.RequestTimeoutException;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 
 * @author karl
 */
@Slf4j
public class MyClientPoolMessagerProcessor extends SimpleChannelInboundHandler<P2PWrapper> {

    private final ExecutorService executorService;

    private final ArrayBlockingQueue<P2PWrapper> requestQueue;

    private final P2PNode node;//同一服务器(多路复用)
    
    private final Channel channel;
    
    private boolean isConnected = false;
    
    private final SimpleChannelPool channelPool;

    public MyClientPoolMessagerProcessor(P2PNode node, Channel channel,ExecutorService executorService, int queueSize) {
        this.node = node;
        this.channel = channel;
        this.channelPool = node.getChannelPool();
        this.executorService = executorService;
        this.requestQueue = new ArrayBlockingQueue<>(queueSize);
        //this.responseQueue = new ArrayBlockingQueue<>(queueSize);
       
    }

    public BlockingQueue<P2PWrapper> getRequestQueue() {
        return requestQueue;
    }

    public P2PNode getNode() {
        return node;
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        isConnected = false;
        log.error(cause.getMessage(), cause);
        ctx.close();
        this.channelPool.release(channel);
        super.exceptionCaught(ctx, cause);
    }


    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        isConnected = true;
        
        super.handlerAdded(ctx);
    }
    
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        isConnected = false;
        this.channelPool.release(channel);
        node.removeBusinessProcessor(this);
        log.info("channelRemoved:{}", ctx.channel().id());
        //尝试重新处理关联到当前服务器(其他连接通道)的的请求队列

        for (P2PWrapper request : requestQueue) {
            try {
                log.warn("当前连接关闭,使用其他通道处理正在排队中的请求:{}", request);
                ChannelAwaitOnMessage<P2PWrapper> responseFuture = node.getChannelAwaitOnMessage(request);
                if(responseFuture!=null){
                    node.reTryRequest(responseFuture, request);//使用其他通道处理请求 
                }else{
                    log.error( "{} ->\n responseFuture is null for requestId:{}" ,request, request.getSeq());
                }
               
            } catch (Exception ex) {
                log.error("chanel close error ->\n", ex);
                node.completeExceptionally(request, ex);
            }
        }
        requestQueue.clear();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
       //executorService.submit(new MyRequestSender(ctx, requestQueue, RESPONSE_FUTURE_MAP));
        //流水线通道就绪后提交发送队列任务,多路异步处理
        executorService.submit(new MyRequestSendQueue(ctx, requestQueue)); 
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, P2PWrapper response) {
       //服务器异步返回消息
        int requestId = response.getSeq();
        node.complete(requestId, response);
    }

    public P2PWrapper syncWait(P2PWrapper request, long timeout, TimeUnit unit) throws TimeoutException {
        int requestId = request.getSeq();
        //异步等待服务器回应->唤醒->返回结果
        ChannelAwaitOnMessage task = node.pollChannelAwaitOnMessage(request, timeout, unit);
        node.putResponseFuture(requestId, task);
        try {
            //requestQueue.put(request);if full await 无限等待
            //System.out.println("requestQueue.offer:" + request.getSeq());
            //加入异步请求队列,超时异常,由队列长度限流
            boolean success = requestQueue.offer(request, timeout, unit);
            if (!success) {
                 RequestTimeoutException e =new RequestTimeoutException(request.toString());
                node.removeWithException(request,e);
                log.error("request processing timeout -> {}\n  current requestQueue size {}", request, requestQueue.size());
                throw e;
            }
            task.setTimeout(timeout, unit);
            Future<P2PWrapper> future = (Future<P2PWrapper>) ExecutorServicePool.P2P_REFERENCED_CHANNEL_AWAIT_POOLS.submit(task);
            task.setFuture(future);
            P2PWrapper result = future.get(timeout, TimeUnit.MILLISECONDS);
            return result;
        } catch (TimeoutException e) {
            node.removeWithException(request,e);
            throw e;
        } catch (Exception ex) {
            return node.exception(request, ex);
        } 
    }
    
    public Future<P2PWrapper> asyncQueue(P2PWrapper request, long timeout, TimeUnit unit) throws TimeoutException {
        int requestId = request.getSeq();
        //异步等待服务器回应->唤醒->返回结果
            ChannelAwaitOnMessage task = node.pollChannelAwaitOnMessage(request, timeout, unit);
        node.putResponseFuture(requestId, task);
        try {
            //requestQueue.put(request);if full await 无限等待
            //System.out.println("requestQueue.offer:" + request.getSeq());
            //加入异步请求队列,超时异常,由队列长度限流
            boolean success = requestQueue.offer(request, timeout, unit);
            if (!success) {
                 RequestTimeoutException e =new RequestTimeoutException(request.toString());
                node.removeWithException(request,e);
                log.error("request processing timeout -> {}\n  current requestQueue size {}", request, requestQueue.size());
                throw e;
            }
            task.setTimeout(timeout, unit);
            Future<P2PWrapper> future = (Future<P2PWrapper>) ExecutorServicePool.P2P_REFERENCED_CHANNEL_AWAIT_POOLS.submit(task);
           task.setFuture(future);
           return future;
        } catch (RequestTimeoutException e) {
            node.removeWithException(request,e);
            throw e;
        } catch (Exception ex) {
            node.removeWithException(request,ex);
            throw new RuntimeException(ex);
        } 
    }

    public P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request, long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        int requestId = request.getSeq();
        node.putResponseFuture(requestId, responseFuture);
        try {
            //requestQueue.put(request);if full await 无限等待
            //System.out.println("requestQueue.offer:" + request.getSeq());
            //加入异步请求队列
            boolean success = requestQueue.offer(request, timeout, unit);
            if (!success) {
                RequestTimeoutException e =new RequestTimeoutException(request.toString());
                node.removeWithException(request,e);
                log.error("request processing timeout -> {}\n  current requestQueue size {}", request, requestQueue.size());
                throw e;
            }
            Future<P2PWrapper> future = responseFuture.getFuture();
            P2PWrapper result = future.get(timeout, TimeUnit.MILLISECONDS);
            return result;
        } catch (TimeoutException e) {
            node.removeWithException(request,e);
            throw e;
        } catch (Exception ex) {
            return node.exception(request, ex);
        } 
    }


    public Channel getChannel() {
        return channel;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public SimpleChannelPool getChannelPool() {
        return channelPool;
    }

    public void release() {
        isConnected = false;
        channel.close();
        this.channelPool.release(channel);
    }

    @Override
    public int hashCode() {
        
        return this.channel.id().hashCode();
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
        final MyClientPoolMessagerProcessor other = (MyClientPoolMessagerProcessor) obj;
        return channel.id().equals(other.channel.id());
    }
    
    
}
