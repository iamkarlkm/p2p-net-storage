package javax.net.p2p.client.pooled;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author karl
 */
@Slf4j
public class MyRequestSendQueue implements Runnable {

    private final ChannelHandlerContext ctx;
    private final ArrayBlockingQueue<P2PWrapper> requestQueue;

    public MyRequestSendQueue(ChannelHandlerContext ctx, ArrayBlockingQueue<P2PWrapper> requestQueue) {
        this.ctx = ctx;
        this.requestQueue = requestQueue;
    }

    @Override
    public void run() {
        try {
            while (true) {
                P2PWrapper request = requestQueue.take();//if empty await
                ChannelFuture cf = ctx.writeAndFlush(request);
                //System.out.println("MyRequestSender:" + request.getSeq());
                // Wait for response from server
                if (cf != null) {
                    //官方建议优先使用addListener(GenericFutureListener)，而非await()。
                    cf.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) {
                            // 等待直到底层IO执行完毕
                            if (future.isSuccess()) {
                                log.debug("send success -> " + request);
                            } else {
                                try {
                                    log.error("{}消息发送未成功,可能原因:{},关闭channel:{}", request, future.cause(), cf.channel().id());
                                    //去除channel从连接池
                                    Channel channel = future.channel();
                                    channel.close();
                                    //channelPool.release(channel);
                                } catch (Exception ex) {
                                    log.error(ex.getMessage());
                                }
                            }
                        }
                    });
                    cf.sync();//很重要的，因为channel 写进通道以后sync 才会将其发送出去。
                }
                //ctx.read();
//                while (true) {
//                    P2PWrapper response = responseQueue.take();
//                    System.out.println("Received response from server: " + response);
//                    break;
//                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ctx.close();
        }
    }

}
