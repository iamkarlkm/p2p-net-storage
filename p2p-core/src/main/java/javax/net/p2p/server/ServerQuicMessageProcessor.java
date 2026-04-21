package javax.net.p2p.server;

import io.netty.channel.ChannelHandlerContext;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.auth.AuthEnforcer;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.channel.AbstractQuicMessageProcessor;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.interfaces.P2PChannelAwareCommandHandler;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.ConcurrentHashMap;
/**
 * ServerQuicMessageProcessor。
 */

@Slf4j
public class ServerQuicMessageProcessor extends AbstractQuicMessageProcessor {

    protected AbstractP2PServer server;
    protected final ConcurrentHashMap<Integer, AbstractLongTimedRequestAdapter> lastLongTimedRequestAdapterMap = new ConcurrentHashMap<>();
    protected final ConcurrentHashMap<Integer, AbstractStreamRequestAdapter> lastStreamRequestAdapterMap = new ConcurrentHashMap<>();

    public ServerQuicMessageProcessor(AbstractP2PServer server, int magic, int queueSize) {
        super(magic, queueSize);
        this.server = server;
    }

    protected AbstractSendMesageExecutor createExecutor(ChannelHandlerContext ctx) {
        return ServerSendQuicMesageExecutor.build(server, queueSize, ctx.channel());
    }

    @Override
    public void processMessage(ChannelHandlerContext ctx, P2PWrapper request) {
        if (log.isDebugEnabled()) {
            log.debug("request:{}", request);
        }

        log.info("request:{}", request);
        P2PWrapper response;
        P2PWrapper denied = AuthEnforcer.check(ctx.channel(), request);
        if (denied != null) {
            sendResponse(ctx, denied);
            return;
        }
        if (request.getCommand() == P2PCommand.STD_CANCEL || request.getCommand() == P2PCommand.STD_STOP) {
            AbstractLongTimedRequestAdapter longTimed = lastLongTimedRequestAdapterMap.remove(request.getSeq());
            if (longTimed != null) {
                longTimed.asyncProcess(request);
                return;
            }
            AbstractStreamRequestAdapter stream = lastStreamRequestAdapterMap.remove(request.getSeq());
            if (stream != null) {
                stream.asyncProcess(stream, StreamP2PWrapper.buildStream(request.getSeq(), true));
                sendResponse(ctx, P2PWrapper.build(request.getSeq(), P2PCommand.STD_CANCEL, "canceled"));
                return;
            }
            sendResponse(ctx, P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "task not found"));
            return;
        }
        if (!P2PServiceManager.isEnabled(request.getCommand().getCategory())) {
            P2PWrapper unavailable = P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "service unavailable: " + request.getCommand().getCategory());
            sendResponse(ctx, unavailable);
            return;
        }
        P2PCommandHandler handler = HANDLER_REGISTRY_MAP.get(request.getCommand());
        if (handler != null) {
            if (handler instanceof AbstractLongTimedRequestAdapter) {//如果是耗时操作,比如文件io等,提交给异步线程池处理
                AbstractLongTimedRequestAdapter longTimed = lastLongTimedRequestAdapterMap.get(request.getSeq());
                if (longTimed == null) {
                    longTimed = (AbstractLongTimedRequestAdapter) handler;
                    //新建异步消息执行器
                    longTimed = longTimed.asyncProcess(createExecutor(ctx), longTimed, request);
                    lastLongTimedRequestAdapterMap.put(request.getSeq(), longTimed);
                    sendResponse(ctx, P2PWrapper.build(request.getSeq(), P2PCommand.STD_ACCEPTED, null));
                    return;
                } else {
                    longTimed.asyncProcess(request);
                }
                return;
            } else if (handler instanceof AbstractStreamRequestAdapter) {//如果是流操作,比如大文件分段下载/消息订阅等,提交给异步线程池处理
                AbstractStreamRequestAdapter stream = lastStreamRequestAdapterMap.get(request.getSeq());
                if (stream == null) {
                    stream = (AbstractStreamRequestAdapter) handler;
                    //新建异步流消息执行器
                    stream = stream.asyncProcess(createExecutor(ctx), stream, (StreamP2PWrapper) request);
                    lastStreamRequestAdapterMap.put(request.getSeq(), stream);
                    sendResponse(ctx, P2PWrapper.build(request.getSeq(), P2PCommand.STREAM_ACK, null));
                    return;
                } else {
                    stream.asyncProcess(stream,request);
                }
                return;

            } else {//实时短操作,不用另外启动异步任务线程,直接在channel事件循环主线程执行并立即返回结果,可以节约线程切换开销
                if (handler instanceof P2PChannelAwareCommandHandler) {
                    response = ((P2PChannelAwareCommandHandler) handler).process(ctx, request);
                } else {
                    response = handler.process(request);
                }
            }

        } else {
            response = P2PWrapper.build(request.getSeq(), P2PCommand.STD_UNKNOWN, "消息处理器不存在或未注册：" + request);
        }
        if (log.isDebugEnabled()) {
            log.debug("sendResponse:{}", response);
        }
        log.info("response:{}", response);
        sendResponse(ctx, response);
    }
}
