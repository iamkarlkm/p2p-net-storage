package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.interfaces.StreamRequest;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.rpc.pubsub.proto.PubSubSubscribeRequest;
import javax.net.p2p.rpc.model.RpcRequestContext;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcStatus;
import javax.net.p2p.rpc.proto.RpcStatusCode;
import javax.net.p2p.rpc.server.RpcServerInterceptors;
import javax.net.p2p.rpc.server.RpcFrames;
import javax.net.p2p.rpc.server.RpcPubSubBroker;
import javax.net.p2p.rpc.server.RpcPubSubServices;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 长生命周期事件流入口。
 */
@Slf4j
public class RpcEventCommandServerHandler extends AbstractStreamRequestAdapter implements StreamRequest {
    private String topic;
    private RpcRequestContext requestContext;

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.RPC_EVENT;
    }

    @Override
    public StreamP2PWrapper request(AbstractSendMesageExecutor executor, StreamP2PWrapper message) {
        RpcFrame frame = null;
        try {
            frame = RpcFrame.parseFrom((byte[]) message.getData());
            requestContext = RpcRequestContext.from(message, frame, executor.getChannel());
            RpcStatus intercepted = beforeHandle(requestContext);
            if (intercepted != null) {
                continued = false;
                executor.sendResponse(StreamP2PWrapper.buildStream(
                    message.getSeq(),
                    message.getIndex(),
                    P2PCommand.RPC_EVENT,
                    RpcFrames.complete(frame, new byte[0], intercepted, true, requestContext).toByteArray(),
                    true
                ));
                return null;
            }
            if (!RpcPubSubServices.SERVICE.equals(frame.getMeta().getService())
                || !RpcPubSubServices.METHOD_SUBSCRIBE.equals(frame.getMeta().getMethod())) {
                continued = false;
                afterError(RpcStatusCode.METHOD_NOT_ALLOWED, "unsupported RPC_EVENT method");
                sendErrorResponse(executor, message, frame, RpcStatusCode.METHOD_NOT_ALLOWED, "unsupported RPC_EVENT method");
                return null;
            }
            PubSubSubscribeRequest request = PubSubSubscribeRequest.parseFrom(frame.getPayload());
            topic = request.getTopic();
            if (message.getIndex() == 0) {
                log.info("rpc.event subscribe open seq={} topic={}", message.getSeq(), topic);
                boolean subscribed = RpcPubSubBroker.subscribe(topic, message.getSeq(), executor, frame);
                if (!subscribed) {
                    continued = false;
                    afterError(RpcStatusCode.BAD_REQUEST, "rpc event subscribe rejected");
                    sendErrorResponse(executor, message, frame, RpcStatusCode.BAD_REQUEST, "rpc event subscribe rejected");
                }
            }
            return null;
        } catch (Exception ex) {
            continued = false;
            afterError(RpcStatusCode.INTERNAL_ERROR, ex.toString());
            try {
                sendErrorResponse(executor, message, frame, RpcStatusCode.INTERNAL_ERROR, ex.toString());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    @Override
    public void cancel(AbstractSendMesageExecutor executor, StreamP2PWrapper message) {
        if (topic != null) {
            RpcPubSubBroker.unsubscribe(topic, message.getSeq(), executor);
        }
    }

    @Override
    public void processStream(AbstractSendMesageExecutor executor, P2PWrapper request) {
    }

    private void sendErrorResponse(
        AbstractSendMesageExecutor executor,
        StreamP2PWrapper message,
        RpcFrame requestFrame,
        RpcStatusCode code,
        String errorMessage
    ) throws InterruptedException {
        RpcFrame frame = RpcFrames.error(requestFrame == null ? RpcFrame.getDefaultInstance() : requestFrame, code, errorMessage, false);
        executor.sendResponse(StreamP2PWrapper.buildStream(
            message.getSeq(),
            message.getIndex(),
            P2PCommand.RPC_EVENT,
            frame.toByteArray(),
            true
        ));
    }

    private RpcStatus beforeHandle(RpcRequestContext context) {
        for (javax.net.p2p.rpc.api.RpcServerInterceptor interceptor : RpcServerInterceptors.all()) {
            RpcStatus status = interceptor.beforeHandle(context);
            if (status != null) {
                return status;
            }
        }
        return null;
    }

    private void afterError(RpcStatusCode code, String message) {
        if (requestContext == null) {
            return;
        }
        for (javax.net.p2p.rpc.api.RpcServerInterceptor interceptor : RpcServerInterceptors.all()) {
            interceptor.afterError(requestContext, code, message);
        }
    }
}
