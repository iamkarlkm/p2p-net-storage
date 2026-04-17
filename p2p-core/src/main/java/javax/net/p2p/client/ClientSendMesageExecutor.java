package javax.net.p2p.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoopGroup;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author karl
 */
@Slf4j
public abstract class ClientSendMesageExecutor extends AbstractSendMesageExecutor implements Runnable {

   

    protected final Condition awaitCondition;//建立一个异步等待、唤醒条件
    protected final ReentrantLock lock;

   public ClientSendMesageExecutor(int queueSize) {
        super(queueSize);
        this.queueSize = queueSize;
        this.lock = new ReentrantLock();
        awaitCondition = lock.newCondition();
        ExecutorServicePool.createClientPools();
    }

//    public ClientSendMesageExecutor(P2PMessageService client, int queueSize, int magic) {
//        super(client, queueSize, magic);
//        this.magic = magic;
//        this.queueSize = queueSize;
//        this.lock = new ReentrantLock();
//        this.awaitCondition = lock.newCondition();
//         ExecutorServicePool.createClientPools();
//    }

    @Override
    public void run() {
        try {
            while (channel.isActive()) {
//                System.out.println("channel:"+channel.toString());
                P2PWrapper request = requestQueue.take();//if empty await
//                System.out.println("request:"+request.toString());
                ChannelFuture cf = writeAndFlush(channel,request);
                //System.out.println("Wait for response from server");
                // Wait for response from server
                if (cf != null) {
                    //官方建议优先使用addListener(GenericFutureListener)，而非await()。
                    cf.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) {
                            // 等待直到底层IO执行完毕
                            if (future.isSuccess()) {
                                log.info("send success -> " + request);
                                if (full && requestQueue.isEmpty()) {
                                    full = false;
                                    notifyEmpty();
                                }
                            } else {
                                try {
                                    log.error("{}消息发送未成功,可能原因:{},关闭channel:{}", request, future.cause(), cf.channel().id());
                                    Channel channel = future.channel();
                                    channel.close();
                                } catch (Exception ex) {
                                    log.error(ex.getMessage());
                                }
                            }
                        }
                    });
                    cf.sync();//很重要的，因为channel 写进通道以后sync 才会将其发送出去。
                }
                //this.awaitCondition.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            connected = false;
            channel.close();
        }
    }

    /**
     * 建立连接,不阻塞调用者(启动一个线程,监听连接关闭)
     *
     * @param io_work_group
     * @param bootstrap
     */
    @Override
    public abstract void connect(EventLoopGroup io_work_group, Bootstrap bootstrap);
    
    public abstract ChannelFuture writeAndFlush(Channel channel,P2PWrapper request)  throws InterruptedException;

    @Override
    public boolean isConnected() {
        return connected;
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
        final ClientSendMesageExecutor other = (ClientSendMesageExecutor) obj;
        return Objects.equals(this.channel, other.channel);
    }

}
