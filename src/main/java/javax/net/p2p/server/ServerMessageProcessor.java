package javax.net.p2p.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractTcpMessageProcessor;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServerMessageProcessor extends AbstractTcpMessageProcessor {

    public ServerMessageProcessor(int magic, int queueSize) {
        super(magic, queueSize);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, P2PWrapper msg) throws Exception {

        int seq = msg.getSeq();
        log.debug("channel {} read msg ->\n{}", ctx.channel().id(), msg);
        //System.out.println(request);
        P2PWrapper p2p = null;
        //System.out.println(registryMap);
        //System.out.println(P2PCommand.GET_FILE.equals(request.getCommand()));
        P2PCommandHandler handler = (P2PCommandHandler) HANDLER_REGISTRY_MAP.get(msg.getCommand());
        //System.out.println(handler);
        if (handler != null) {
            p2p = handler.process(msg);
        } else {
            //System.out.println(SERVER_HANDLER_REGISTRY_MAP);
            p2p = P2PWrapper.build(msg.getSeq(), P2PCommand.STD_ERROR, "未知消息类型：" + msg);
        }

        try {
            //			ctx.writeAndFlush(p2p);
            log.debug("channel {} send msg ->\n{}", ctx.channel().id(), p2p);
            ChannelFuture cf = ctx.channel().writeAndFlush(p2p);
            if (cf != null) {//消息回应->
                //官方建议优先使用addListener(GenericFutureListener)，而非await()。
                cf.addListener(new ChannelFutureListener() {
                    public void operationComplete(ChannelFuture future) {
                        // 等待直到底层IO执行完毕
                        //log.info(seq+":future.isSuccess() -> "+future.isSuccess());
                        if (!future.isSuccess()) {

                            // }else{
                            log.error("{}消息处理未成功,可能原因:{},关闭channel:{}", seq, future.cause().getMessage(), ctx.channel().id());
                            //ctx.close();
                        }
                    }
                });
                cf.sync();//很重要的，因为channel 写进通道以后sync 才会将其发送出去。

            }
        } catch (Exception ex) {
            Channel channel = ctx.channel();
            log.warn("{}消息回应异常:{},channel:{}", seq, ex.getMessage(), channel.id());
            try {
                //由于系统缓冲区空间不足或队列已满，不能执行套接字上的操作
                Thread.sleep(30 * 1000l);
                ChannelFuture cf = ctx.channel().writeAndFlush(p2p);
                cf.sync();//很重要的，因为channel 写进通道以后sync 才会将其发送出去。
                log.warn("{}消息回应异常:{},等待重发成功:{}", seq, ex.getMessage(), channel.id());
            } catch (Exception ex2) {
                log.error(ex2.getMessage());
                try {
                    ctx.close();
                } catch (Exception ex3) {
                    log.error(ex2.getMessage());
                }
                log.error(seq + "号消息回应异常！", ex);
            }
        } finally {
            //ctx.channel();
        }

    }

}
