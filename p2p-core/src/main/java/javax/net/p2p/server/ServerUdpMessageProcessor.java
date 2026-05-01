package javax.net.p2p.server;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.auth.AuthEnforcer;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.channel.AbstractUdpMessageProcessor;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.rpc.server.RpcControlSupport;
import lombok.extern.slf4j.Slf4j;
/**
 * ServerUdpMessageProcessor。
 */

@Slf4j
public class ServerUdpMessageProcessor extends AbstractUdpMessageProcessor {

//    public ServerUdpMessageProcessor(int magic,int queueSize) {
//        super(magic,queueSize);
//    }
    //private final ServerSendUdpMesageExecutor serverSendMesageExecutor;
    
//    private final ConcurentPooledObjects<ServerSendUdpMesageExecutor> serverSendMesageExecutorPool;
    
    protected AbstractP2PServer server;

    public ServerUdpMessageProcessor(AbstractP2PServer server, int magic, int queueSize) {
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

    protected AbstractSendMesageExecutor createExecutor(ChannelHandlerContext ctx, InetSocketAddress remote, int magic) {
        return ServerSendUdpMesageExecutor.build(server, queueSize, remote, ctx.channel(), magic);
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
        P2PWrapper denied = AuthEnforcer.check(ctx.channel(), request);
        if (denied != null) {
            sendResponse(ctx.channel(), datagramPacket.sender(), denied, magic);
            return;
        }

        if (request.getCommand() == P2PCommand.STD_CANCEL || request.getCommand() == P2PCommand.STD_STOP) {
            InetSocketAddress remote = datagramPacket.sender();
            ConcurrentHashMap<Integer, AbstractLongTimedRequestAdapter> longMap = lastLongTimedRequestAdapterMap.get(remote);
            if (longMap != null) {
                AbstractLongTimedRequestAdapter longTimed = longMap.remove(request.getSeq());
                if (longTimed != null) {
                    longTimed.asyncProcess(request);
                    return;
                }
            }
            ConcurrentHashMap<Integer, AbstractStreamRequestAdapter> streamMap = lastStreamRequestAdapterMap.get(remote);
            if (streamMap != null) {
                AbstractStreamRequestAdapter stream = streamMap.remove(request.getSeq());
                if (stream != null) {
                    stream.asyncProcess(stream, StreamP2PWrapper.buildStream(request.getSeq(), true));
                    response = P2PWrapper.build(request.getSeq(), P2PCommand.STD_CANCEL, "canceled");
                    sendResponse(ctx.channel(), datagramPacket.sender(), response, magic);
                    return;
                }
            }
            response = P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "task not found");
            sendResponse(ctx.channel(), datagramPacket.sender(), response, magic);
            return;
        }
        if (request.getCommand() == P2PCommand.RPC_CONTROL) {
            InetSocketAddress remote = datagramPacket.sender();
            ConcurrentHashMap<Integer, AbstractLongTimedRequestAdapter> longMap = lastLongTimedRequestAdapterMap.computeIfAbsent(remote, ignored -> new ConcurrentHashMap<>());
            ConcurrentHashMap<Integer, AbstractStreamRequestAdapter> streamMap = lastStreamRequestAdapterMap.computeIfAbsent(remote, ignored -> new ConcurrentHashMap<>());
            response = RpcControlSupport.handleControl((P2PWrapper<byte[]>) request, longMap, streamMap);
            sendResponse(ctx.channel(), datagramPacket.sender(), response, magic);
            return;
        }
        if (!P2PServiceManager.isEnabled(request.getCommand().getCategory())) {
            response = P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "service unavailable: " + request.getCommand().getCategory());
            sendResponse(ctx.channel(), datagramPacket.sender(), response, magic);
            return;
        }
        P2PCommandHandler handler = HANDLER_REGISTRY_MAP.get(request.getCommand());
        if (handler != null) {
            if (handler instanceof AbstractLongTimedRequestAdapter) {//如果是耗时操作,比如文件io等,提交给异步线程池处理
                InetSocketAddress remote = datagramPacket.sender();
                ConcurrentHashMap<Integer, AbstractLongTimedRequestAdapter> longMap = lastLongTimedRequestAdapterMap.computeIfAbsent(remote, k -> new ConcurrentHashMap<>());
                AbstractLongTimedRequestAdapter longTimed = longMap.get(request.getSeq());
                if (longTimed == null) {
                    longTimed = (AbstractLongTimedRequestAdapter) handler;
                    //新建异步消息执行器
                    AbstractSendMesageExecutor executor = createExecutor(ctx, datagramPacket.sender(), magic);
                    if (executor instanceof ServerSendUdpMesageExecutor) {
                        asyncSendUdpMesageExecutorMap.put(datagramPacket.sender(), (ServerSendUdpMesageExecutor) executor);
                    }
                    longTimed = longTimed.asyncProcess(executor, longTimed, request);
                    longMap.put(request.getSeq(), longTimed);
                    response = P2PWrapper.build(request.getSeq(), P2PCommand.STD_ACCEPTED, null);
                    sendResponse(ctx.channel(), datagramPacket.sender(), response, magic);
                    return;
                } else {
                    longTimed.asyncProcess(request);
                }
                return;
            } else if (handler instanceof AbstractStreamRequestAdapter) {//如果是流操作,比如大文件分段下载/消息订阅等,提交给异步线程池处理
                InetSocketAddress remote = datagramPacket.sender();
                ConcurrentHashMap<Integer, AbstractStreamRequestAdapter> streamMap = lastStreamRequestAdapterMap.computeIfAbsent(remote, k -> new ConcurrentHashMap<>());
                AbstractStreamRequestAdapter stream = streamMap.get(request.getSeq());
                if (stream == null) {
                    stream = (AbstractStreamRequestAdapter) handler;
                    //新建异步流消息执行器
                    AbstractSendMesageExecutor executor = createExecutor(ctx, datagramPacket.sender(), magic);
                    if (executor instanceof ServerSendUdpMesageExecutor) {
                        asyncSendUdpMesageExecutorMap.put(datagramPacket.sender(), (ServerSendUdpMesageExecutor) executor);
                    }
                    stream = stream.asyncProcess(executor, stream, (StreamP2PWrapper) request);
                    streamMap.put(request.getSeq(), stream);
                    response = P2PWrapper.build(request.getSeq(), P2PCommand.STREAM_ACK, null);
                    sendResponse(ctx.channel(), datagramPacket.sender(), response, magic);
                    return;
                } else {
                    stream.asyncProcess(stream,request);
                }
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
