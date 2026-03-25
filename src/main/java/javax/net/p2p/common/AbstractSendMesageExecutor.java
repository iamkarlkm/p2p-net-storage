package javax.net.p2p.common;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoopGroup;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.p2p.codec.P2PWrapperEncoder;
import javax.net.p2p.common.pool.PooledableAdapter;
import javax.net.p2p.exception.RequestTimeoutException;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.server.AbstractP2PServer;

/**
 *
 * @author karl
 */
@Slf4j
public abstract class AbstractSendMesageExecutor extends PooledableAdapter implements Runnable {

    
    protected static final long DEFAULT_TIMEOUT = 300000;
    protected static final int DEFAULT_QUEUESIZE = 4096;
    protected static final int DEFAULT_CORESIZE = 2;
    protected static final int DEFAULT_MAGIC  = P2PWrapperEncoder.MAGIC;
    /**
     * 存储channel对应的流消息序列号对应的当前window最小index(已发送)
     */
    // private final ConcurrentMap<Integer, Integer> messageServiceResponseStreamWindowIndexMap = new ConcurrentHashMap<>(4096);
    /**
     * 存储channel对应的流消息序列号对应的当前未发送的消息集合
     */
    protected P2PMessageService messageService;
    
    protected Channel channel;

    protected int magic;

    protected Integer queueSize;

    protected ArrayBlockingQueue<P2PWrapper> responseQueue;
    
    protected final ArrayBlockingQueue<P2PWrapper> requestQueue;

    //private final Condition awaitCondition;//建立一个异步等待、唤醒条件
    //protected ReentrantLock lock;

    protected boolean connected;
    protected boolean full;
    private Future<?> future;
    
     //最近发送消息缓存
    protected P2PWrapper lastMessage;

    protected ChannelFuture lastMessageFuture = null;
    

    protected AbstractSendMesageExecutor(int queueSize) {
        this.responseQueue = new ArrayBlockingQueue(queueSize);
        this.requestQueue = new ArrayBlockingQueue(queueSize);
    }

    public AbstractSendMesageExecutor(P2PMessageService messageService, int queueSize, int magic) {
        this.messageService = messageService;

        this.magic = magic;
        this.queueSize = queueSize;
        this.responseQueue = new ArrayBlockingQueue(queueSize);
        this.requestQueue = new ArrayBlockingQueue(queueSize);
        //this.lock = new ReentrantLock();
        //this.awaitCondition = lock.newCondition();
    }

    @Override
    public void run() {
        try {
            while (connected && isActive()) {
                System.out.println("AbstractSendMesageExecutor wait messege ...");
                P2PWrapper response = responseQueue.take(); //if empty await
                System.out.println("writeMessage(response):" + response);
                writeMessage(response);
                System.out.println(" after writeMessage(response):" + response);
            }
        } catch (InterruptedException ex) {
            log.error(ex.getMessage());
        } finally {
            connected = false;
            channel.close();
            notifyClosed();
            release();//回归对象池
        }
    }

    @Override
    public void clear() {
        connected = false;
        channel = null;
        if(future!=null && !future.isDone()){
            future.cancel(true);
        }
        future = null;
        lastMessage = null;
        lastMessageFuture =null;
    }
    
    
    public void sendMessage(P2PWrapper message) throws InterruptedException {
        responseQueue.put(message);
    }

    public void writeMessage(P2PWrapper message) throws InterruptedException {
        lastMessage = message;
        lastMessageFuture = channel.writeAndFlush(message);
        // Wait for response from messageService
        if (lastMessageFuture != null) {
            //官方建议优先使用addListener(GenericFutureListener)，而非await()。
            lastMessageFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) {
                    // 等待直到底层IO执行完毕
                    if (future.isSuccess()) {
                        if (log.isDebugEnabled()) {
                            log.debug("send success -> " + message);
                        }

                        if (full && responseQueue.isEmpty()) {
                            full = false;
                            notifyEmpty();
                        }
                    } else {
                        try {
                            log.error("{}消息发送未成功,可能原因:{},关闭channel:{}", message, future.cause(), channel.id());
                            Channel channel = future.channel();
                            channel.close();
                        } catch (Exception ex) {
                            log.error(ex.getMessage());
                        }
                    }
                }
            });
            lastMessageFuture.sync();//很重要的，因为channel 写进通道以后sync 才会将其发送出去。
        }
    }

    public void start(Channel channel) {
        this.channel = channel;
//        this.remote = (InetSocketAddress) channel.remoteAddress();
        //.connected = channel.isActive();
        future = ExecutorServicePool.SERVER_ASYNC_POOLS.submit(this);
    }
    
    

    public Channel getChannel() {
        return channel;
    }

    public boolean isActive() {
        return channel.isActive();
    }

    public void reTryByOtherChannel() {
        for (P2PWrapper request : responseQueue) {
            try {
                log.warn("当前连接关闭,使用其他通道处理正在排队中的请求:{}", request);
                ChannelAwaitOnMessage<P2PWrapper> responseFuture = messageService.getChannelAwaitOnMessage(request);
                if(responseFuture!=null){
                    messageService.reTryRequest(responseFuture, request);//使用其他通道处理请求 
                }else{
                    log.error( "{} ->\n responseFuture is null for requestId:{}" ,request, request.getSeq());
                }
               
            } catch (Exception ex) {
                log.error("chanel close error ->\n", ex);
                messageService.completeExceptionally(request, ex);
            }
        }
        responseQueue.clear();
    }

   /**
     * 统一处理:执行断线重连/未完成消息重传等
     */
    protected void notifyClosed() {
        messageService.handleMesageExecutorClose(this);
    }

    /**
     * 通知队列从满变空
     */
    protected void notifyEmpty() {
        messageService.handleMesageExecutorQueueEmpty(this);
    }
    
    /**
     * requestQueue.put(request);等待timeout
     *
     * @param request
     * @param timeout
     * @param unit
     * @return
     * @throws TimeoutException
     */
    public P2PWrapper addQueueIfNotFull(P2PWrapper request, long timeout, TimeUnit unit) throws TimeoutException {
        try {
            //异步等待服务器回应->唤醒->返回结果
            ChannelAwaitOnMessage task = messageService.pollChannelAwaitOnMessage(request, timeout, unit);
            messageService.putResponseFuture(request.getSeq(), task);
            //requestQueue.put(request);等待timeout
            //加入异步请求队列
            boolean success = requestQueue.offer(request);
            if (!success) {
                log.error("加入异步请求队列失败,移除ResponseFuture requestId = {}",request.getSeq());
                messageService.removeResponseFuture(request.getSeq());
                full = true;
                return null;
            }

            task.setTimeout(timeout, unit);
            Future<P2PWrapper> future = (Future<P2PWrapper>) ExecutorServicePool.CHANNEL_AWAIT_POOLS.submit(task);
            task.setFuture(future);
            P2PWrapper result = future.get(timeout, unit);
            return result;
        } catch (TimeoutException e) {
            messageService.removeWithException(request, e);
            throw e;
        } catch (Exception ex) {
            return messageService.exception(request, ex);
        }
    }
    
    /**
     * requestQueue.put(request);if full await 无限等待
     *
     * @param request
     * @param timeout
     * @param unit
     * @return
     * @throws TimeoutException
     */
    public P2PWrapper syncExcute(P2PWrapper request, long timeout, TimeUnit unit) throws TimeoutException {
        try {
            Future<P2PWrapper> future = asyncExcute(request, timeout, unit);
            if (timeout > 0) {
                return future.get(timeout, unit);
            } else {
                return future.get();
            }
        } catch (TimeoutException e) {
            messageService.removeWithException(request, e);
            throw e;
        } catch (Exception ex) {
            return messageService.exception(request, ex);
        }
    }

    public Future<P2PWrapper> asyncExcute(P2PWrapper request, long timeout, TimeUnit unit) {
        int requestId = request.getSeq();
        System.out.println("requestId:"+requestId);
        try {
            //异步等待服务器回应->唤醒->返回结果
            ChannelAwaitOnMessage task = messageService.pollChannelAwaitOnMessage(request, timeout, unit);
            messageService.putResponseFuture(requestId, task);
            System.out.println("client.putResponseFuture:"+requestId);
            //requestQueue.put(request);if full await 无限等待/timeout>0 等待timeout
            if (timeout > 0) {
                //加入异步请求队列
                boolean success = requestQueue.offer(request, timeout, unit);
                if (!success) {
                    log.error("加入异步请求队列失败,移除ResponseFuture requestId = {}",requestId);
                    messageService.removeResponseFuture(requestId);
                    RequestTimeoutException e = new RequestTimeoutException(request.toString());
                    throw e;
                }
            } else {
                requestQueue.put(request);
            }
            Future<P2PWrapper> future = (Future<P2PWrapper>) ExecutorServicePool.CHANNEL_AWAIT_POOLS.submit(task);
            task.setFuture(future);
            return future;
        } catch (Exception ex) {
            log.error(ex.getMessage());
            throw new RuntimeException(ex);
        }
    }

    public P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> task, P2PWrapper request, long timeout, TimeUnit unit) throws TimeoutException {
        try {
            //requestQueue.put(request);if full await 无限等待
            messageService.putResponseFuture(request.getSeq(), task);
            task.resetTimeout(timeout,unit);
            requestQueue.put(request);
            P2PWrapper result = task.getFuture().get(timeout, unit);
            return result;
        } catch (TimeoutException e) {
            messageService.removeWithException(request, e);
            throw e;
        } catch (Exception ex) {
            return messageService.exception(request, ex);
        }
    }

    public void sendResponse(P2PWrapper response) throws InterruptedException {
        System.out.println("responseQueue.put(response) => " + responseQueue.size());
        //requestQueue.put(request);if full await 无限等待
        responseQueue.put(response);
    }

//    public void recieveOrdered(StreamP2PWrapper msg) throws InterruptedException {
//        lock.lock();
//
//        Integer index = messageServiceResponseStreamWindowIndexMap.get(msg.getSeq());
//        try {
//
//            if (index == null) {
//                index = msg.getIndex();
//                messageServiceResponseStreamWindowIndexMap.put(msg.getSeq(), index);
//            } else {
//                messageServiceResponseStreamWindowIndexMap.put(msg.getSeq(), index++);
//            }
//
//        } finally {
//            lock.unlock();
//        }
//        if (msg.getIndex() == index) {
//            //requestQueue.put(request);if full await 无限等待
//            responseQueue.put(msg);
//        } else {
//
//        }
//
//    }
    public void sendResponse(P2PWrapper response, long timeout, TimeUnit unit) throws TimeoutException, InterruptedException {
        //requestQueue.put(request);if full await 等待超时
        responseQueue.offer(response, timeout, unit);
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
        final AbstractSendMesageExecutor other = (AbstractSendMesageExecutor) obj;
        return Objects.equals(this.channel, other.channel);
    }

    public boolean isConnected() {
        return this.connected;
    }

    public abstract void connect(EventLoopGroup io_work_group, Bootstrap bootstrap);

}
