package javax.net.p2p.client;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.pool.SimpleChannelPool;
import java.util.concurrent.Future;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.CacheChannelRemovalListener;
import javax.net.p2p.common.ChannelAwaitOnMessage;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.interfaces.P2PClientService;
import javax.net.p2p.exception.RequestTimeoutException;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 客户端消息处理器,需要维护传入channel的生命周期
 * netty踩坑指南:流水线处理器有且只能有一个处理器(并且是最后一个处理器)继承SimpleChannelInboundHandler
 * 因为此抽象类在子类执行完channelRead0方法释放ReferenceCounted的泛型对象(比如BuyeBuf),中间处理器应该继承
 * ChannelInboundHandlerAdapter或其常用子类ByteToMessageDecoder
 * @author karl
 */
@Slf4j
public class ClientMessageProcessor extends SimpleChannelInboundHandler<P2PWrapper> {

//    public final static ConcurrentHashMap<P2PCommand, P2PCommandHandler> CLIENT_HANDLER_REGISTRY_MAP = new ConcurrentHashMap<>();
//
//	private static final List<String> CLASS_CACHE = new ArrayList<>();
    private P2PClient client;

    

    public P2PClient getClient() {
        return client;
    }

    public void setClient(P2PClient client) {
        this.client = client;
    }
    private boolean isConnected = false;
    private long lastActiveTime;

//private final Map<Channel, P2PWrapper> SYNC_CHANNEL_RESULT_MAPS = new ConcurrentHashMap<>();
    private final static Cache<Integer, ChannelAwaitOnMessage<P2PWrapper>> SYNC_CHANNEL_THREAD_CACHE = CacheBuilder.newBuilder()
        .expireAfterAccess(30, TimeUnit.MINUTES).removalListener(new CacheChannelRemovalListener()).build();

    /**
     * 等待服务器消息异步锁
     */
    private final ArrayBlockingQueue<ChannelAwaitOnMessage<P2PWrapper>> AWAIT_ON_MESSAGE_LOCKS = new ArrayBlockingQueue(4096);

    private int timeoutCount = 0;

    private int timeOutCountLimit = 6;

    public ClientMessageProcessor(P2PClient client) {
        this.client = client;
        this.lastActiveTime = System.currentTimeMillis();
    }

    /**
     * 同步等待服务器回应消息
     * 
     * @param channelPool
     * @param channel
     * @param request P2PWrapper
     * @param timeout
     * @return
     */
    public P2PWrapper syncWait(SimpleChannelPool channelPool, Channel channel, P2PWrapper request, long timeout) {
        Integer seq = request.getSeq();
        try {
            ChannelFuture cf = channel.writeAndFlush(request);
            if (cf != null) {
                //官方建议优先使用addListener(GenericFutureListener)，而非await()。
                cf.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) {
                        // 等待直到底层IO执行完毕
                        //log.info(seq+":future.isSuccess() -> "+future.isSuccess());
                        if (future.isSuccess()) {
                            //消息事件已完成,释放对应channel
                            log.debug("socket io operationComplete request:{}", request);
                        } else {
                            try {
                                log.warn("{}消息发送未成功,可能原因:{},关闭channel:{}", seq, future.cause(), channel.id());
                                //去除channel从连接池
                                channel.close();
                                channelPool.release(channel);
                            } catch (Exception ex) {
                                log.error(ex.getMessage());
                            }
                        }
                    }
                });
                //cf.sync();//很重要的，因为channel 写进通道以后sync 才会将其发送出去。
                //异步等待服务器回应
                channel.read();
            }
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            log.warn("{}消息发送异常:{},关闭channel:{}", seq, ex.getMessage(), channel.id());
            try {
                channel.close();
                //去除channel从连接池
                channelPool.release(channel);
            } catch (Exception ex2) {
                log.error(ex2.getMessage());
            }
            return exception(request, ex);
        }

        try {
            //异步等待服务器回应,线程被中断表示本次交互已完成,返回结果
            //Thread.sleep(timeout);
            ChannelAwaitOnMessage<P2PWrapper> task = AWAIT_ON_MESSAGE_LOCKS.poll();
            if (task != null) {
                if(task.isFree()){
                    task.setTimeout(timeout);
                }else{
                    AWAIT_ON_MESSAGE_LOCKS.offer(task);
                   task = new ChannelAwaitOnMessage(channelPool, timeout); 
                }
            } else {
                task = new ChannelAwaitOnMessage(channelPool, timeout);
            }
            SYNC_CHANNEL_THREAD_CACHE.put(seq, task);
            Future<P2PWrapper> future = (Future<P2PWrapper>) ExecutorServicePool.CHANNEL_AWAIT_POOLS.submit(task);
            P2PWrapper result = future.get(timeout, TimeUnit.MILLISECONDS);

            return result;
        } catch (InterruptedException ex) {
            timeoutCount = 0;
            return exception(request,  ex);
        } catch (ExecutionException ex) {
            //去除channel从连接池
            channel.close();
            channelPool.release(channel);
            return exception(request,  ex);
        } catch (TimeoutException ex) {
            //服务器回应超时控制
            timeoutCount++;
            while (isConnected && timeoutCount <= timeOutCountLimit) {
                try {
                    log.warn(seq + "请求回应超时,当前{}次，上限{}次", timeoutCount, timeOutCountLimit);
                    ChannelAwaitOnMessage<P2PWrapper> task = SYNC_CHANNEL_THREAD_CACHE.getIfPresent(request.getSeq());
                    Future<P2PWrapper> future = (Future<P2PWrapper>) ExecutorServicePool.CHANNEL_AWAIT_POOLS.submit(task);
                    P2PWrapper result = future.get(timeout, TimeUnit.MILLISECONDS);
                    return result;
                } catch (TimeoutException ex1) {
                    timeoutCount++;
                } catch (Exception ex1) {
                    try {
                        //去除channel从连接池
                        channel.close();
                        channelPool.release(channel);
                        log.warn(seq + "请求回应超时,当前{}次，上限{}次，关闭channel:{}", timeoutCount, timeOutCountLimit, channel.id());
                        return exception(request,  ex1);
                    } catch (Exception ex2) {
                        log.error(ex.getMessage());
                    }
                }

            }
            try {
                //去除channel从连接池
                channel.close();
                channelPool.release(channel);
                log.warn(seq + "请求回应超时,当前{}次，上限{}次，关闭channel:{}", timeoutCount, timeOutCountLimit, channel.id());
            } catch (Exception ex2) {
                log.error(ex.getMessage());
            }
            return exception(request,  new RequestTimeoutException(seq + "号消息回应超时！"));
        }
    }

    private P2PWrapper exception(P2PWrapper request, Exception e) {
        ChannelAwaitOnMessage<P2PWrapper> task = SYNC_CHANNEL_THREAD_CACHE.getIfPresent(request.getSeq());
        if (task != null) {
            SYNC_CHANNEL_THREAD_CACHE.invalidate(request.getSeq());
            //服务器回应消息一一对应放入缓存,并唤醒对应客户端线程
            //addSyncResult(channel, msg);
            SYNC_CHANNEL_THREAD_CACHE.invalidate(request.getSeq());
            //task.completeExceptionally(e);
            AWAIT_ON_MESSAGE_LOCKS.offer(task);
        }
        
        P2PWrapper exception = P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.getClass().getCanonicalName() + ":" + e.getMessage());
        return exception;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, P2PWrapper response) throws Exception {
       
        log.info("channel {} read msg ->\n{}", ctx.channel().id(), response);
        Channel channel = ctx.channel();
        //Thread t = SYNC_CHANNEL_THREAD_MAPS.remove(channel);
        ChannelAwaitOnMessage<P2PWrapper> task = SYNC_CHANNEL_THREAD_CACHE.getIfPresent(response.getSeq());
        if (task != null) {
            //服务器回应消息一一对应放入缓存,并唤醒对应客户端线程
            //addSyncResult(channel, msg);
            SYNC_CHANNEL_THREAD_CACHE.invalidate(response.getSeq());
            task.complete(response);
            AWAIT_ON_MESSAGE_LOCKS.offer(task);
        } else {
            try {
                log.error(response.getSeq() + "意外回应错误,未找到对应处理线程,channel:{},response:{}", channel.id(), response);
            } catch (Exception ex2) {
                log.error(ex2.getMessage());
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error(cause.getMessage(), cause);
        ctx.close();
        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        isConnected = false;
        super.handlerRemoved(ctx);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        isConnected = true;
        super.handlerAdded(ctx);
    }


   
}
