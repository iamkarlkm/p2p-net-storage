package javax.net.p2p.client.pooled;

import javax.net.p2p.channel.ChannelUtils;
import io.netty.channel.Channel;
import io.netty.channel.pool.AbstractChannelPoolMap;
import io.netty.channel.pool.SimpleChannelPool;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.ChannelAwaitOnMessage;
import javax.net.p2p.interfaces.P2PClientService;
import javax.net.p2p.exception.RequestTimeoutException;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.StopWatch;

/**
 *
 * @author karl
 */
@Slf4j
public class P2PNode implements  Closeable {

    /*
	 * 默认客户端每个请求超时90秒
     */
    private static final long DEFAULT_TIMEOUT = 90;

    private final InetSocketAddress remoteAddress;

    private SimpleChannelPool channelPool;

    private final AbstractChannelPoolMap<InetSocketAddress, SimpleChannelPool> channelPoolMap;

//    private final ArrayBlockingQueue<MyClientPoolMessagerProcessor> activeBusinessMwssageProcessor;
    /**
     * 同一请求的服务器返回消息可能在其他通道异步返回,需同一服务器集中存储
     */
    private final ConcurrentMap<Integer, ChannelAwaitOnMessage<P2PWrapper>> RESPONSE_FUTURE_MAP = new ConcurrentHashMap<>();
    
     
    /**
     * 等待服务器消息异步锁
     */
    private final ArrayBlockingQueue<ChannelAwaitOnMessage<P2PWrapper>> awaitOnMessageLocks;
    

    private final List<MyClientPoolMessagerProcessor> businessMessageProcessors = new ArrayList<>();

    private static final long FAIL_WAITING_TIMES = 15 * 60 * 1000;//15分钟

    private boolean isServerFailed = false;

    private int current;

    //private final Timer heartPongTimer;
    public P2PNode(InetSocketAddress remoteAddress, AbstractChannelPoolMap<InetSocketAddress, SimpleChannelPool> channelPoolMap, int queueSize) {
        this.remoteAddress = remoteAddress;
        this.channelPoolMap = channelPoolMap;
        this.channelPool = channelPoolMap.get(remoteAddress);
//        this.activeBusinessMwssageProcessor = new ArrayBlockingQueue(queueSize);
         awaitOnMessageLocks = new ArrayBlockingQueue(queueSize);

    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }
    //	public void startngHandover(){
    //		isStartngHandover = true;
    //		try{
    //			   Thread.sleep(200l);	
    //			}catch(InterruptedException e){
    //				log.error(e.getMessage());
    //			}
    //	}

    //	public void handover(InetSocketAddress remoteAddress, BlockingQueue<P2PWrapper> requestQueue) throws InterruptedException, ExecutionException, TimeoutException{
    //		this.remoteAddress = remoteAddress;
    //		
    //		heartPongFailedCount = 0;
    //		isStartngHandover = false;
    //	}
    public final P2PWrapper excute(P2PWrapper request) throws Exception {
        return excute(request, DEFAULT_TIMEOUT, TimeUnit.SECONDS);
    }

    public final Future<P2PWrapper> asyncExcute(P2PWrapper request) throws Exception {
        return asyncExcute(request, DEFAULT_TIMEOUT, TimeUnit.SECONDS);
    }

    public final P2PWrapper excute(P2PWrapper request, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        while (isServerFailed) {
            try {
                Thread.sleep(FAIL_WAITING_TIMES);
            } catch (InterruptedException e) {
                log.error(e.getMessage());
            } finally {
                isServerFailed = false;
            }
        }
        MyClientPoolMessagerProcessor clientHandler = null;
        try {
            //从已有业务处理器集或对应连接池获取一个空闲业务处理器
            clientHandler = pollBusinessProcessor(timeout, unit);
            return clientHandler.syncWait(request, timeout, unit);
        } catch (TimeoutException | RequestTimeoutException ex) {
            //尝试重新连接服务器
            channelPoolMap.remove(remoteAddress);
            this.channelPool = channelPoolMap.get(remoteAddress);
            try {
                clientHandler = pollBusinessProcessor(timeout, unit);
                return clientHandler.syncWait(request, timeout, unit);
            } catch (TimeoutException ex2) {
                log.error("The server {} is can not connected -> {},{}" + this.remoteAddress, request, ex2);
                isServerFailed = true;
                throw ex2;
            }
        } catch (Exception e) {
            log.error("send failed -> {},{}" + request, e.getMessage());
            removeWithException(request,e);
            throw e;
        }

    }

    public final Future<P2PWrapper> asyncExcute(P2PWrapper request, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        while (isServerFailed) {
            try {
                Thread.sleep(FAIL_WAITING_TIMES);
            } catch (InterruptedException e) {
                log.error(e.getMessage());
            } finally {
                isServerFailed = false;
            }
        }
        MyClientPoolMessagerProcessor clientHandler = null;
        try {
            //从已有业务处理器集或对应连接池获取一个空闲业务处理器
            clientHandler = pollBusinessProcessor(timeout, unit);
            return clientHandler.asyncQueue(request, timeout, unit);
        } catch (TimeoutException | RequestTimeoutException ex) {
            //尝试重新连接服务器
            channelPoolMap.remove(remoteAddress);
            this.channelPool = channelPoolMap.get(remoteAddress);
            try {
                //从已有业务处理器集或对应连接池获取一个空闲业务处理器
                clientHandler = pollBusinessProcessor(timeout, unit);
                return clientHandler.asyncQueue(request, timeout, unit);
            } catch (TimeoutException ex2) {
                log.error("The server {} is can not connected -> {},{}" + this.remoteAddress, request, ex2);
                isServerFailed = true;
                throw ex2;
            }
        } catch (Exception e) {
            log.error("send failed -> " + request, e);
                removeWithException(request,e);
            throw e;
        }
    }

    public final P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request) throws Exception {
        return reTryRequest(responseFuture, request, DEFAULT_TIMEOUT, TimeUnit.SECONDS);
    }

    public final P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        while (isServerFailed) {
            try {
                Thread.sleep(FAIL_WAITING_TIMES);
            } catch (InterruptedException e) {
                log.error(e.getMessage());
            } finally {
                isServerFailed = false;
            }
        }
        MyClientPoolMessagerProcessor clientHandler = null;
        try {
            //从已有业务处理器集或对应连接池获取一个空闲业务处理器
            clientHandler = pollBusinessProcessor(timeout, unit);
            return clientHandler.reTryRequest(responseFuture, request, timeout, unit);
        } catch (TimeoutException | RequestTimeoutException ex) {
            //尝试重新连接服务器
            channelPoolMap.remove(remoteAddress);
            this.channelPool = channelPoolMap.get(remoteAddress);
            try {
                //从已有业务处理器集或对应连接池获取一个空闲业务处理器
                clientHandler = pollBusinessProcessor(timeout, unit);
                return clientHandler.reTryRequest(responseFuture, request, timeout, unit);
            } catch (TimeoutException ex2) {
                log.error("The server {} is can not connected -> {},{}" + this.remoteAddress, request, ex2);
                isServerFailed = true;
                throw ex2;
            }
        } catch (Exception e) {
            log.error("send failed -> " + request, e);
            removeWithException(request,e);
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        //this.channelPoolMap.close();
        //heartPongTimer.cancel();
        for (MyClientPoolMessagerProcessor processor : businessMessageProcessors) {
            processor.release();
        }
        this.channelPool.close();
    }

    public void offerBusinessProcessor(MyClientPoolMessagerProcessor prossor) {
        businessMessageProcessors.add(prossor);
    }

    public void removeBusinessProcessor(MyClientPoolMessagerProcessor prossor) {
        businessMessageProcessors.remove(prossor);
    }

    /**
     * 从已有业务处理器集或对应连接池获取一个空闲业务处理器(简单轮询负载平衡)
     *
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    public MyClientPoolMessagerProcessor pollBusinessProcessor(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (businessMessageProcessors.isEmpty()) {//消息处理器为空,表示连接未建立,建立连接
            Future<Channel> future = channelPool.acquire();
            Channel channel = future.get(timeout, unit);
            return ChannelUtils.getMyClientPoolMessagerProcessor(channel);
        }
        if (current++ >= businessMessageProcessors.size()) {
            current = 0;
        }
        try {
            MyClientPoolMessagerProcessor p = businessMessageProcessors.get(current);
            if (p.isConnected()) {
                return p;
            } else {
                p.release();
                businessMessageProcessors.remove(p);
                return pollBusinessProcessor(timeout, unit);
            }
        } catch (Exception e) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {

            }
            return pollBusinessProcessor(timeout, unit);
        }
    }

    public static String echo(P2PNode myClient, String msg) throws Exception {

        P2PWrapper p2p = P2PWrapper.build(P2PCommand.ECHO, msg);
        P2PWrapper response = (P2PWrapper) myClient.excute(p2p);
        //P2PCommandHandler handler = (P2PCommandHandler) registryMap.get(response.getCommand());
        //System.out.println(handler);
        //if(handler!=null){
        if (response.getCommand().getValue() == P2PCommand.ECHO.getValue()) {

            return (String) response.getData();
        }
        //}
        throw new RuntimeException("服务器内部错误：" + response);
    }

    public static void test(P2PNode myClient, int count) throws Exception {
        StopWatch stopWatch = new StopWatch();
        System.out.println("test执行开始...");
        stopWatch.start();
        for (int i = 0; i < count; i++) {
            //System.out.println("echo -> "+i);
            System.out.println(echo(myClient, "test-" + i));
        }
        stopWatch.stop();
        // 统计执行时间（秒）
        System.out.println("执行时长：" + stopWatch.getTime() / 1000 + " 秒.");
        // 统计执行时间（毫秒）
        System.out.println("执行时长：" + stopWatch.getTime() + " 毫秒.");
        // 统计执行时间（纳秒）
        System.out.println("执行时长：" + stopWatch.getNanoTime() + " 纳秒.");
    }

    public SimpleChannelPool getChannelPool() {
        return channelPool;
    }

    public void setChannelPool(SimpleChannelPool channelPool) {
        this.channelPool = channelPool;
    }

    //@Override
    public InetSocketAddress getRemoteServer() {
        return remoteAddress;
    }

    public ConcurrentMap<Integer, ChannelAwaitOnMessage<P2PWrapper>> getResponseFutureMap() {
        return RESPONSE_FUTURE_MAP;
    }
    
    public void putResponseFuture(int requestId,ChannelAwaitOnMessage<P2PWrapper> task){
        RESPONSE_FUTURE_MAP.put(requestId, task);
    }
    
    public ChannelAwaitOnMessage pollChannelAwaitOnMessage(P2PWrapper request, long timeout, TimeUnit unit){
        ChannelAwaitOnMessage<P2PWrapper> task = awaitOnMessageLocks.poll();
            if (task != null) {
                if(task.isFree()){
                    task.setTimeout(timeout, unit);
                }else{
                    awaitOnMessageLocks.offer(task);
                   task = new ChannelAwaitOnMessage(channelPool, timeout); 
                }
            } else {
                task = new ChannelAwaitOnMessage(channelPool, timeout);
            }
        return task;
    }
    
    public P2PWrapper exception(P2PWrapper request, Exception e) {
        ChannelAwaitOnMessage<P2PWrapper> task = RESPONSE_FUTURE_MAP.remove(request.getSeq());
        if (task != null) {
            //服务器回应消息一一对应放入缓存,并唤醒对应客户端线程
            //task.completeExceptionally(e);
            task.completeExceptionally(e);
            awaitOnMessageLocks.offer(task);
        }
        
        P2PWrapper exception = P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.getClass().getCanonicalName() + ":" + e.getMessage());
        return exception;
    }
    
    public ChannelAwaitOnMessage<P2PWrapper> removeWithException(P2PWrapper request, Exception e) {
        ChannelAwaitOnMessage<P2PWrapper> responseFuture = RESPONSE_FUTURE_MAP.remove(request.getSeq());
        //释放channel,以便复用
        //channelPool.release(channel);
        if (responseFuture != null) {
            responseFuture.completeExceptionally(e);
            awaitOnMessageLocks.offer(responseFuture);

        }
        return responseFuture;

    }
    
    public ChannelAwaitOnMessage<P2PWrapper> getChannelAwaitOnMessage(P2PWrapper request) {
        return RESPONSE_FUTURE_MAP.get(request.getSeq());
        

    }
        
    public void completeExceptionally(P2PWrapper request,Exception exception) {
        ChannelAwaitOnMessage<P2PWrapper> responseFuture = RESPONSE_FUTURE_MAP.remove(request.getSeq());
        //释放channel,以便复用
        //channelPool.release(channel);
        if (responseFuture != null) {
            responseFuture.completeExceptionally(exception);
            awaitOnMessageLocks.offer(responseFuture);
        }else{
            log.error( "{} ->\n responseFuture is null for requestId:{}" ,request, request.getSeq());
        }
    }
    
    public void complete(int requestId,P2PWrapper response) {
        ChannelAwaitOnMessage<P2PWrapper> responseFuture = RESPONSE_FUTURE_MAP.remove(requestId);
        //释放channel,以便复用
        //channelPool.release(channel);
        if (responseFuture != null) {
            responseFuture.complete(response);
            awaitOnMessageLocks.offer(responseFuture);
        }else{
            log.error( "responseFuture is null for requestId:{}" ,requestId);
        }
    }

    /**
     * 获取当前总连接数
     * @return 
     */
    public int getTotalConnects() {
        return businessMessageProcessors.size();
    }
    

}
