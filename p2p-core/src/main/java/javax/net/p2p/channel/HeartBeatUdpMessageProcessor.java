package javax.net.p2p.channel;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import static io.netty.handler.timeout.IdleState.ALL_IDLE;
import static io.netty.handler.timeout.IdleState.READER_IDLE;
import io.netty.handler.timeout.IdleStateEvent;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 客户端消息处理器,需要维护传入channel的生命周期
 * netty踩坑指南:流水线处理器有且只能有一个处理器(并且是最后一个处理器)继承SimpleChannelInboundHandler
 * 因为此抽象类在子类执行完channelRead0方法释放ReferenceCounted的泛型对象(比如BuyeBuf),中间处理器应该继承
 * ChannelInboundHandlerAdapter或其常用子类ByteToMessageDecoder,覆盖channelRead(继续向下传递消息,需ctx.fireChannelRead(msg))
 *
 * @author karl
 */
@Slf4j
public class HeartBeatUdpMessageProcessor extends AbstractUdpMessageProcessor {

    private boolean isConnected = false;
    private long lastActiveTime;
    private final long timeout;

    /**
     * 指定毫秒间隔发送心跳包
     *
     * @param timeout
     * @param magic
     */
    public HeartBeatUdpMessageProcessor(long timeout,int magic) {
        super(magic, 4096);
        this.timeout = timeout;
        this.lastActiveTime = System.currentTimeMillis();
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

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // IdleStateHandler 所产生的 IdleStateEvent 的处理逻辑.
        if (isConnected) {
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent e = (IdleStateEvent) evt;
                switch (e.state()) {
                    case READER_IDLE:
                    case WRITER_IDLE:
                    case ALL_IDLE:
                        handleIdle(ctx);
                        break;
                    default:
                        break;
                }
            }
        } else {
            ctx.close();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.debug("{} channelActive. Channel ID: {}", ctx.channel().remoteAddress(), ctx.channel().id());
        //attempts = 0;
        //lastActiveTime = System.currentTimeMillis();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.debug("{} channelInactive. Channel ID: {}", ctx.channel().remoteAddress(), ctx.channel().id());
//		 if (reconnect) {
//            int timeout = 2 << attempts; // Exponential backoff
//            timer.newTimeout(this, timeout, TimeUnit.MILLISECONDS);
//            attempts++;
//        }
        super.channelInactive(ctx);
    }

    /**
     * 处理所有空闲事件
     *
     * @param ctx
     */
    protected void handleIdle(ChannelHandlerContext ctx) {
        //System.err.println("---READER_IDLE---");
        long now = System.currentTimeMillis();
        if (now - lastActiveTime > 15000) { // 15 seconds without read activity
            //3次心跳检测失败，关闭连接
            log.error("3次心跳检测失败，关闭连接 -> " + ctx.name());
            ctx.channel().close();
        } else if (isConnected && (now - lastActiveTime > timeout)) { // 5 seconds without read activity
            //log.info("心跳检测 -> " + ctx.name());
            //TODO udp channel 无remoteAddress
            //sendPingMsg(ctx);
            if(this.messageService!=null){
                System.out.println(" sendPingMsg " +this.messageService.getRemote());
            }
            System.out.println(" sendPingMsg " + ctx.channel().localAddress() + " : " + ctx.channel().remoteAddress());
        }
    }

//    protected void handleWriterIdle(ChannelHandlerContext ctx) {
//        //System.err.println("---WRITER_IDLE---");
//    }
//
//    protected void handleAllIdle(ChannelHandlerContext ctx) {
//         //System.err.println("---ALL_IDLE---");
//    }
    private static final P2PWrapper HEART_PING = P2PWrapper.build(0, P2PCommand.HEART_PING);
//	private static final byte[] HEART_PING_BYTES = SerializationUtil.serialize(HEART_PING);

    private static final P2PWrapper HEART_PONG = P2PWrapper.build(0, P2PCommand.HEART_PONG);
//	private static final byte[] HEART_PONG_BYTEBUF = SerializationUtil.serialize(HEART_PONG);

    protected void sendPingMsg(ChannelHandlerContext ctx, DatagramPacket datagramPacket) {
        Channel channel = ctx.channel();
         if(log.isDebugEnabled()){
           log.debug("channel {} send ping->\n{}", channel.id(), HEART_PING);
        }
        sendResponse(ctx, datagramPacket, HEART_PING);
        //heartbeatCount++;
        //System.out.println(name + " sent ping msg to " + context.channel().remoteAddress() + ", count: " + heartbeatCount);
    }

    private void sendPongMsg(ChannelHandlerContext ctx, DatagramPacket datagramPacket) {
        Channel channel = ctx.channel();
        if(log.isDebugEnabled()){
           log.debug("channel {} send pong->\n{}", channel.id(), HEART_PONG); 
        }
        sendResponse(ctx, datagramPacket, HEART_PONG);
        
    }

//	 @Override
//    public void run(Timeout timeout) throws Exception {//断线重连任务实现
//        ChannelFuture future = bootstrap.connect(host, port);
//        future.addListener(new ChannelFutureListener() {
//            @Override
//            public void operationComplete(ChannelFuture future) throws Exception {
//                if (!future.isSuccess()) {
//                    channelInactive(future.channel().pipeline().context(ConnectionWatchdog.this));
//                } else {
//                    attempts = 0;
//                    lastActiveTime = System.currentTimeMillis();
//                }
//            }
//        });
//    }

    /**
     * 继续向下传递消息必须调用ctx.fireChannelRead(msg)或父类channelRead 否则中断流水线处理
     *
     * @param ctx
     * @param datagramPacket
     * @param msg
     */
    @Override
    public void processMessage(ChannelHandlerContext ctx, DatagramPacket datagramPacket, P2PWrapper msg) {
        if (msg instanceof P2PWrapper) {
            P2PWrapper response = (P2PWrapper) msg;
            if (P2PCommand.HEART_PING == response.getCommand()) {
                lastActiveTime = System.currentTimeMillis();
                sendPongMsg(ctx,datagramPacket);
                ctx.fireChannelReadComplete();
                return;
            } else if (P2PCommand.HEART_PONG == response.getCommand()) {//
                lastActiveTime = System.currentTimeMillis();
                ctx.fireChannelReadComplete();
                return;
            }
        }
        ctx.fireChannelRead(msg);//继续向下传递消息
    }

   
}
