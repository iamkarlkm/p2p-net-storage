package javax.net.p2p.rpc.server;

import com.google.protobuf.Message;
import java.lang.reflect.Method;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.rpc.api.RpcServerStreamInvoker;
import javax.net.p2p.rpc.model.RpcMethodDescriptor;
import javax.net.p2p.rpc.model.RpcMethodKey;
import javax.net.p2p.rpc.model.RpcRequestContext;
import javax.net.p2p.rpc.proto.RpcCallType;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcStatusCode;

/**
 * 基于现有流适配器的 RPC 服务端流最小实现。
 */
public class RpcServerStreamHandler extends AbstractStreamRequestAdapter {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.RPC_STREAM;
    }

    @Override
    public void processStream(AbstractSendMesageExecutor executor, P2PWrapper request) throws InterruptedException {
        try {
            if (!(request instanceof StreamP2PWrapper<?> streamRequest)) {
                executor.sendResponse(P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "RPC_STREAM 仅支持 StreamP2PWrapper"));
                return;
            }
            RpcFrame frame = RpcFrame.parseFrom((byte[]) streamRequest.getData());
            RpcRequestContext context = RpcRequestContext.from(request, frame, executor.getChannel());
            if (context.isDeadlineExceeded(System.currentTimeMillis())) {
                sendError(executor, streamRequest, frame, RpcStatusCode.DEADLINE_EXCEEDED, "deadline exceeded");
                return;
            }
            RpcMethodDescriptor descriptor = RpcBootstrap.registry().find(new RpcMethodKey(
                context.service(),
                context.method(),
                context.version()
            ));
            if (descriptor == null) {
                sendError(executor, streamRequest, frame, RpcStatusCode.NOT_FOUND, "RPC 方法不存在");
                return;
            }
            if (descriptor.callType() != RpcCallType.SERVER_STREAM) {
                sendError(executor, streamRequest, frame, RpcStatusCode.METHOD_NOT_ALLOWED, "仅支持 server stream 方法");
                return;
            }
            Message requestMessage = parseMessage(descriptor.requestType(), frame.getPayload().toByteArray());
            @SuppressWarnings("unchecked")
            RpcServerStreamInvoker<Message, Message> invoker = (RpcServerStreamInvoker<Message, Message>) descriptor.invoker();
            RpcQueuedFrameSender frameSender = new RpcQueuedFrameSender(
                executor,
                streamRequest.getSeq(),
                P2PCommand.RPC_STREAM,
                frame,
                null
            );
            RpcServerResponseObserver observer = new RpcServerResponseObserver(frameSender, frame);
            invoker.invoke(context, requestMessage, observer);
        } catch (InterruptedException ex) {
            throw ex;
        } catch (Exception ex) {
            executor.sendResponse(P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, ex.toString()));
        }
    }

    private void sendError(
        AbstractSendMesageExecutor executor,
        StreamP2PWrapper<?> streamRequest,
        RpcFrame frame,
        RpcStatusCode code,
        String message
    ) throws InterruptedException {
        RpcFrame errorFrame = RpcFrames.error(frame, code, message, false);
        executor.sendResponse(StreamP2PWrapper.buildStream(
            streamRequest.getSeq(),
            0,
            P2PCommand.RPC_STREAM,
            errorFrame.toByteArray(),
            true
        ));
    }

    private Message parseMessage(Class<? extends Message> messageType, byte[] payload) throws Exception {
        Method parseFrom = messageType.getMethod("parseFrom", byte[].class);
        return (Message) parseFrom.invoke(null, payload == null ? new byte[0] : payload);
    }
}
