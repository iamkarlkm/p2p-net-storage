package javax.net.p2p.server;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.channel.AbstractUdpMessageProcessor;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServerUdpMessageProcessor extends AbstractUdpMessageProcessor {

//    public ServerUdpMessageProcessor(int magic,int queueSize) {
//        super(magic,queueSize);
//    }
    //private final ServerSendUdpMesageExecutor serverSendMesageExecutor;
    
//    private final ConcurentPooledObjects<ServerSendUdpMesageExecutor> serverSendMesageExecutorPool;
    
    protected P2PServerUdp server;

    public ServerUdpMessageProcessor(P2PServerUdp server, int magic, int queueSize) {
         super(magic, queueSize);
         this.server = server;
        //立即建立一个可用的异步执行器池
        //serverSendMesageExecutor = new ServerSendUdpMesageExecutor(server, queueSize, magic);
        
//        serverSendMesageExecutorPool = new ConcurentPooledObjects(new PooledObjectFactory<ServerSendUdpMesageExecutor>(){
//                       
//            @Override
//            public ServerSendUdpMesageExecutor newInstance() {
//                return new ServerSendUdpMesageExecutor(server, queueSize, magic);
//            }
//            
//        });

    }

  
    /**
     * 处理客户端请求消息
     *
     * @param ctx
     * @param datagramPacket
     * @param request
     */
    @Override
    public void processMessage(ChannelHandlerContext ctx, DatagramPacket datagramPacket, P2PWrapper request) {
        if (log.isDebugEnabled()) {
            log.debug("request:{}", request);
        }
        
        log.info("request:{}", request);
        P2PWrapper response;
        P2PCommandHandler handler = HANDLER_REGISTRY_MAP.get(request.getCommand());
        if (handler != null) {
            if (handler instanceof AbstractLongTimedRequestAdapter) {//如果是耗时操作,比如文件io等,提交给异步线程池处理
                AbstractLongTimedRequestAdapter longTimed = lastLongTimedRequestAdapterMap.get(request.getSeq());
                if (longTimed == null) {
                    longTimed = (AbstractLongTimedRequestAdapter) handler;
                    //新建异步消息执行器
                    longTimed = longTimed.asyncProcess(ServerSendUdpMesageExecutor.build(server, queueSize, datagramPacket.sender(), ctx.channel()), longTimed, request);
                    lastLongTimedRequestAdapterMap.put(request.getSeq(), longTimed);
                } else {
                    longTimed.asyncProcess(request);
                }
                return;
            } else if (handler instanceof AbstractStreamRequestAdapter) {//如果是流操作,比如大文件分段下载/消息订阅等,提交给异步线程池处理
                AbstractStreamRequestAdapter stream = lastStreamRequestAdapterMap.get(request.getSeq());
                if (stream == null) {
                    stream = (AbstractStreamRequestAdapter) handler;
                    //新建异步流消息执行器
                    stream.asyncProcess(ServerSendUdpMesageExecutor.build(server, queueSize, datagramPacket.sender(), ctx.channel()), stream, (StreamP2PWrapper) request);
                    lastStreamRequestAdapterMap.put(request.getSeq(), stream);
                } else {
                    stream.asyncProcess(stream,request);
                }
                //response = P2PWrapper.builder(request.getSeq(), P2PCommand.STREAM_ACK, null);
                return;

            } else {//实时短操作,不用另外启动异步任务线程,直接在channel事件循环主线程执行并立即返回结果,可以节约线程切换开销
                response = handler.process(request);
            }

        } else {
            switch (request.getCommand()) {
                case UDP_FRAME_ACK:
                    completeLastResponse(datagramPacket.sender());
                    return;
                case UDP_FRAME_RESET:
                    ChannelFuture future = lastSendMessageChannelFutureMap.remove(datagramPacket.sender());
                    if(future!=null){//取消socket数据传输
                        future.cancel(true);
                    }
                    retrieveLastResponse(ctx, datagramPacket.sender());
                    return;
                default:
                    response = P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "消息处理器不存在或未注册：" + request);
                    break;
            }
            
        }
        if (log.isDebugEnabled()) {
            log.debug("sendResponse:{}", response);
        }
        sendResponse(ctx.channel(), datagramPacket.sender(), response, magic);
    }



}
