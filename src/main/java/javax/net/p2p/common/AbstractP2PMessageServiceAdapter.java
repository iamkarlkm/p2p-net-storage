package javax.net.p2p.common;

import javax.net.p2p.client.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractStreamResponseAdapter;
import javax.net.p2p.codec.P2PWrapperEncoder;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.common.ChannelAwaitOnMessage;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.common.ReferencedSingleton;
import javax.net.p2p.exception.RequestTimeoutException;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.interfaces.StreamResponse;
import javax.net.p2p.model.CancelP2PWrapper;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;

/**
 * 单一客户端多路(channel池)连接单一服务器
 * @author karl
 */
@Slf4j
public abstract class AbstractP2PMessageServiceAdapter extends ReferencedSingleton implements P2PMessageService {

 
    /**
     * 同一请求的服务器返回消息可能在其他通道异步返回,需同一服务器集中存储
     */
    private final ConcurrentMap<Integer, ChannelAwaitOnMessage<P2PWrapper>> RESPONSE_FUTURE_MAP = new ConcurrentHashMap<>();
    
    /**
     * 流消息序列号对应的回调消息处理器
     */
    protected static final ConcurrentMap<Integer, AbstractStreamResponseAdapter> STREAM_RESPONSE_HANDLER_MAP = new ConcurrentHashMap<>(4096);

    protected InetSocketAddress remoteServer;
    
    protected EventLoopGroup io_work_group;

    protected Bootstrap bootstrap;
    
    protected static final int CHANNEL_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;

    protected final List<AbstractSendMesageExecutor> sendMesageExecutors = new ArrayList<>(CHANNEL_POOL_SIZE);
    /**
     * 并发增删clientSendMesageExecutors锁
     */
    protected final ReentrantLock mesageExecutorSyncLock = new ReentrantLock();

    protected static final long DEFAULT_TIMEOUT = 300000;
    protected static final int DEFAULT_QUEUESIZE = 4096;
    protected static final int DEFAULT_CORESIZE = 2;
    protected static final int DEFAULT_MAGIC  = P2PWrapperEncoder.MAGIC;

   protected final AtomicInteger messageSequence = new AtomicInteger();

    protected static final long FAIL_WAITING_TIMES = 15 * 60 * 1000;//15分钟

    protected boolean isConnectionFailed = false;

    protected int current;

    protected int coreSize;
    protected int queueSize;
    
   /**
     * 应用数据包验证以及自定义动态协议标记
     */
    protected int magic;

    protected int queueFullSize = 0;

    public AbstractP2PMessageServiceAdapter(int queueSize,  int coreSize, int magic) {
        this.queueSize = queueSize;
        this.coreSize = coreSize;
        this.magic = magic;
    }

    
    

    @Override
    public P2PWrapper excute(P2PWrapper request) throws Exception {
        return excute(request, 0, TimeUnit.SECONDS);
    }
    
    @Override
    public void cancelExcute(int requestId)  {
        try {
            excute(new CancelP2PWrapper(requestId), DEFAULT_TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    @Override
    public Future<P2PWrapper> asyncExcute(P2PWrapper request) throws Exception {
        return asyncExcute(request, 0, TimeUnit.SECONDS);
    }
    
     private void checkConnectionFaile() {
        while (isConnectionFailed) {
            try {
                log.error("serverFailed -> FAIL_WAITING_TIMES={}",FAIL_WAITING_TIMES);
                //TODO 设定连接失败多少次抛异常到外层
                Thread.sleep(FAIL_WAITING_TIMES);
            } catch (InterruptedException e) {
                log.error(e.getMessage());
            } finally {
                isConnectionFailed = false;
            }
        }
    }
     
    public abstract AbstractSendMesageExecutor newSendMesageExecutorToQueue();

     @Override
    public P2PWrapper excute(P2PWrapper request, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        checkConnectionFaile();
        request.setSeq(messageSequence.incrementAndGet());
        AbstractSendMesageExecutor mesageExecutor = null;
        try {
            //从已有业务处理器集或对应连接池获取一个空闲业务处理器
            mesageExecutor = pollMesageExecutor(timeout, unit);
            return mesageExecutor.syncExcute(request, timeout, unit);
        } catch (TimeoutException | RequestTimeoutException ex) {
            //尝试重新连接服务器
            try {
                mesageExecutor = pollMesageExecutor(timeout, unit);
                //寻找或新增一个异步执行消息处理器,如果CHANNEL_POOL_SIZE的队列全满,则同步等待队列空闲
                P2PWrapper result = mesageExecutor.addQueueIfNotFull(request, timeout, unit);
                while (result == null) {//业务处理器消息队列满
                    mesageExecutorSyncLock.lock();
                    try {
                        queueFullSize++;
                        //如果业务处理器池未满
                        if (queueFullSize < CHANNEL_POOL_SIZE) {
                            //如果可以新增连接
                            if (queueFullSize == sendMesageExecutors.size()) {

                                newSendMesageExecutorToQueue();

                                mesageExecutor = pollMesageExecutor(timeout, unit);
                                result = mesageExecutor.addQueueIfNotFull(request, timeout, unit);
                            } else {
                                result = mesageExecutor.syncExcute(request, timeout, unit);
                                break;
                            }

                        }
                    } finally {
                        mesageExecutorSyncLock.unlock();
                    }
                }
                return result;
            } catch (TimeoutException ex2) {
                log.error("The channel {} is can not connected,isConnectionFailed = true -> {},{}" + mesageExecutor.getChannel(), request, ex2.getMessage());
                isConnectionFailed = true;
                throw ex2;
            }
        } catch (Exception e) {
            log.error("send failed -> {},{}" + request, e.getMessage());
            removeWithException(request,e);
            throw e;
        }

    }

    @Override
    public Future<P2PWrapper> asyncExcute(P2PWrapper request, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        checkConnectionFaile();
        request.setSeq(messageSequence.incrementAndGet());
        AbstractSendMesageExecutor mesageExecutor = null;
        try {
            //从已有业务处理器集或对应连接池获取一个空闲业务处理器
            mesageExecutor = pollMesageExecutor(timeout, unit);
            return mesageExecutor.asyncExcute(request, timeout, unit);
        } catch (Exception e) {
            log.error("send failed -> " + request, e);
            removeWithException(request,e);
            throw e;
        }
    }
    
    /**
     * (文件下载/订阅)流消息返回,异步回调
     * @param request
     * @param streamMessage 异步回调
     * @return
     * @throws Exception 
     */
//    @Override
    @Override
    public P2PWrapper streamRequest(P2PWrapper request,AbstractStreamResponseAdapter streamMessage) throws Exception {
        checkConnectionFaile();
        if(request.getSeq()==0){
            request.setSeq(messageSequence.incrementAndGet());
        }
        AbstractSendMesageExecutor mesageExecutor = null;
        try {
            //从已有业务处理器集或对应连接池获取一个空闲业务处理器
            mesageExecutor = pollMesageExecutor(0, TimeUnit.SECONDS);
            STREAM_RESPONSE_HANDLER_MAP.put(request.getSeq(), streamMessage);
            return mesageExecutor.syncExcute(request, 0, TimeUnit.SECONDS);
        } catch (TimeoutException | RequestTimeoutException ex) {
            //尝试重新连接服务器
            try {
                mesageExecutor = pollMesageExecutor(0, TimeUnit.SECONDS);
                P2PWrapper result = mesageExecutor.syncExcute(request, 0, TimeUnit.SECONDS);
                return result;
            } catch (TimeoutException ex2) {
                log.error("The channel {} is can not connected,isConnectionFailed = true -> {},{}" + mesageExecutor.getChannel(), request, ex2.getMessage());
                isConnectionFailed = true;
                throw ex2;
            }
        } catch (Exception e) {
            log.error("send failed -> {},{}" + request, e.getMessage());
            removeWithException(request,e);
            throw e;
        }
    }

    @Override
    public Future<P2PWrapper> asyncStreamRequest(P2PWrapper request) throws Exception {
        return asyncExcute(request, 0, TimeUnit.SECONDS);
    }

    @Override
    public P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request) throws InterruptedException, ExecutionException, TimeoutException  {
        return reTryRequest(responseFuture, request, 0, TimeUnit.SECONDS);
    }

   

    @Override
    public P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        checkConnectionFaile();
        AbstractSendMesageExecutor mesageExecutor = null;
        try {
            //从已有业务处理器集或对应连接池获取一个空闲业务处理器
            mesageExecutor = pollMesageExecutor(timeout, unit);
            return mesageExecutor.reTryRequest(responseFuture, request, timeout, unit);
        } catch (TimeoutException | RequestTimeoutException ex) {
            //尝试重新连接服务器
            try {
                //从已有业务处理器集或对应连接池获取一个空闲业务处理器
                mesageExecutor = pollMesageExecutor(timeout, unit);
                return mesageExecutor.reTryRequest(responseFuture, request, timeout, unit);
            } catch (TimeoutException ex2) {
                log.error("The channel {} is can not connected,isConnectionFailed = true -> {},{}" + mesageExecutor.getChannel(), request, ex2.getMessage());
                isConnectionFailed = true;
                throw ex2;
            }
        } catch (Exception e) {
            log.error("send failed -> " + request, e);
            removeWithException(request,e);
            throw e;
        }
    }


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
    @Override
    public AbstractSendMesageExecutor pollMesageExecutor(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        checkConnectionFaile();
        if (sendMesageExecutors.size() > 1) {
            current++;
            if (current >= sendMesageExecutors.size()) {
                current = 0;
            }
        }else{
            //建立第一个可用连接
            newSendMesageExecutorToQueue();
            current = 0;
        }
        AbstractSendMesageExecutor executor;
        try {
             executor = sendMesageExecutors.get(current);
            if (executor.isConnected()) {
                return executor;
            } else {
                mesageExecutorSyncLock.lock();
                try {
                    if (sendMesageExecutors.size() > coreSize) {//如果超过池保留数,移除之
                        if (current >= sendMesageExecutors.size()) {
                            current = 0;
                        }
                        AbstractSendMesageExecutor old = sendMesageExecutors.remove(current);
                        if(old!=null){
                            old.reTryByOtherChannel();
                        }
                    } else {
                        if(remoteServer!=null){//客户端模式:否则重新建立连接
                            executor.connect(io_work_group, bootstrap);
                        }
                    }
                    return pollMesageExecutor(timeout, unit);
                } finally {
                    mesageExecutorSyncLock.unlock();
                }

            }
        } catch (Exception e) {
            log.error("pollMesageExecutor exception: {}, -> isConnectionFailed = true" ,e.getMessage());
            isConnectionFailed = true;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {

            }
            return pollMesageExecutor(timeout, unit);
        }

    }

    @Override
    public InetSocketAddress getRemote() {
        return remoteServer;
    }

    @Override
    public ConcurrentMap<Integer, ChannelAwaitOnMessage<P2PWrapper>> getResponseFutureMap() {
        return RESPONSE_FUTURE_MAP;
    }

    @Override
    public void putResponseFuture(int requestId, ChannelAwaitOnMessage<P2PWrapper> task) {
        RESPONSE_FUTURE_MAP.put(requestId, task);
    }
    
     @Override
    public void removeResponseFuture(int requestId) {
        log.error("freeResponseFuture requestId = {}",requestId);
        ChannelAwaitOnMessage<P2PWrapper> task = RESPONSE_FUTURE_MAP.remove(requestId);
        if(task!=null){
            task.release();
        }
    }

    @Override
    public ChannelAwaitOnMessage pollChannelAwaitOnMessage(P2PWrapper request, long timeout, TimeUnit unit) {
        ChannelAwaitOnMessage<P2PWrapper> task = ChannelAwaitOnMessage.build(timeout,unit);
        return task;
    }
    
    

    @Override
    public P2PWrapper exception(P2PWrapper request, Exception e) {
         checkAndRemoveStreamMessage(request);
        ChannelAwaitOnMessage<P2PWrapper> task = RESPONSE_FUTURE_MAP.remove(request.getSeq());
        log.error("request: {}\n task: {}\n exception: {}",request,task,e.getMessage()!=null?e.getMessage():e.getClass());
        if (task != null) {
            //服务器回应消息一一对应放入缓存,并唤醒对应客户端线程
            //task.completeExceptionally(e);
            task.completeExceptionally(e);
        }
        
        P2PWrapper exception = P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.getClass().getCanonicalName() + ":" + e.getMessage());
        return exception;
    }

    @Override
    public ChannelAwaitOnMessage<P2PWrapper> removeWithException(P2PWrapper request,Exception e) {
         checkAndRemoveStreamMessage(request);
         log.error("removeWithException -> request {}, exception {}",request,e.getMessage()!=null?e.getMessage():e.getClass());
        ChannelAwaitOnMessage<P2PWrapper> responseFuture = RESPONSE_FUTURE_MAP.remove(request.getSeq());
        if (responseFuture != null) {
            responseFuture.completeExceptionally(e);
        }
        return responseFuture;

    }

    @Override
    public ChannelAwaitOnMessage<P2PWrapper> getChannelAwaitOnMessage(P2PWrapper request) {
        return RESPONSE_FUTURE_MAP.get(request.getSeq());
    }

    @Override
    public void completeExceptionally(P2PWrapper request, Exception e) {
        log.error("removeWithException -> request {}, exception {}",request,e.getMessage()!=null?e.getMessage():e.getClass());
        checkAndRemoveStreamMessage(request);
        ChannelAwaitOnMessage<P2PWrapper> responseFuture = RESPONSE_FUTURE_MAP.remove(request.getSeq());
        if (responseFuture != null) {
            responseFuture.completeExceptionally(e);
        } else {
            log.error("{} ->\n responseFuture is null for requestId:{}", request, request.getSeq());
        }
    }

    @Override
    public void complete( P2PWrapper response) {
        log.info("complete response:{}", response);
        if (checkStreamMessage(response)) {//非流消息或流结束,返回调用者
            ChannelAwaitOnMessage<P2PWrapper> responseFuture = RESPONSE_FUTURE_MAP.remove(response.getSeq());
            if (responseFuture != null) {
                responseFuture.complete(response);
            } else {
                log.error("responseFuture is null for response:{}", response);
            }
        }

    }
    
    private boolean checkStreamMessage(P2PWrapper response){
        if (response instanceof StreamP2PWrapper) {//(文件上传/发布)流消息请求处理,异步回调
            log.info("StreamMessage :{}", response);
            StreamP2PWrapper message = (StreamP2PWrapper) response;
            AbstractStreamResponseAdapter callback;
            if(message.isCanceled()){
                callback = STREAM_RESPONSE_HANDLER_MAP.remove(((StreamP2PWrapper) response).getSeq());
                callback.asyncProcess(callback, message);
                return true;
            }else if(message.isCompleted()){
                callback = STREAM_RESPONSE_HANDLER_MAP.remove(((StreamP2PWrapper) response).getSeq());
                callback.asyncProcess(callback, message);
                return true;
            }else{
                callback = STREAM_RESPONSE_HANDLER_MAP.get(((StreamP2PWrapper) response).getSeq());
                //异步线程处理
                callback.asyncProcess(callback, message);
                return false;
            }
             
        }
        return true;
    }
    
    private void checkAndRemoveStreamMessage(P2PWrapper response){
        
        if (response instanceof StreamP2PWrapper) {//(文件上传/发布)流消息请求处理,异步回调
            log.info("StreamMessage :{}", response);
            StreamP2PWrapper message = (StreamP2PWrapper) response;
            StreamResponse callback = STREAM_RESPONSE_HANDLER_MAP.remove(((StreamP2PWrapper) response).getSeq());
            callback.cancel(message);
        }
    }

    @Override
    public void handleConnectSuccess(Channel channel) {
        isConnectionFailed = false;
    }

    @Override
    public void handleConnectFailed(Exception ex) {
        log.error("The server {} handleConnectFailed {},isServerFailed = true" , this.remoteServer,  ex.getMessage());
        isConnectionFailed = true;
    }

    @Override
    public void handleMesageExecutorQueueEmpty(AbstractSendMesageExecutor executor) {
        mesageExecutorSyncLock.lock();
        try {
            queueFullSize--;
        } finally {
            mesageExecutorSyncLock.unlock();
        }
    }
    
    @Override
    public void handleMesageExecutorClose(AbstractSendMesageExecutor executor) {
        mesageExecutorSyncLock.lock();
        try {
            if (sendMesageExecutors.size() > coreSize) {//如果超过池保留数,移除之
                AbstractSendMesageExecutor old = sendMesageExecutors.remove(current);
                old.reTryByOtherChannel();
            } else {//否则重新建立连接
                executor.connect(io_work_group, bootstrap);
            }
        } finally {
            mesageExecutorSyncLock.unlock();
        }
    }

    /**
     * 获取当前可用异步执行总连接数
     *
     * @return
     */
    @Override
    public int getTotalConnects() {
        return sendMesageExecutors.size();
    }
    
   

    @Override
    public void singletonCreated(Object instance) {
        
    }

    @Override
    public void singletonFinalized() {
        log.info("ReferencedSingleton singletonFinalized -> {}", this.toString());
        ExecutorServicePool.releaseClientPools();
    }


    @Override
    public String toString() {
        return this.getClass().getSimpleName()+"{" + "RESPONSE_FUTURE_MAP=" + RESPONSE_FUTURE_MAP + ", queueSize=" + queueSize + ", remoteServer=" + remoteServer + ", isConnectionFailed=" + isConnectionFailed + ", current=" + current + ", coreSize=" + coreSize + ", magic=" + magic + ", queueFullSize=" + queueFullSize + '}';
    }

    
}
