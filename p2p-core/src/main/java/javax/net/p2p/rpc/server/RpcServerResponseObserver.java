package javax.net.p2p.rpc.server;

import com.google.protobuf.Message;
import javax.net.p2p.rpc.api.RpcServerInterceptor;
import javax.net.p2p.rpc.api.RpcServerStreamObserver;
import javax.net.p2p.rpc.model.RpcRequestContext;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.proto.RpcStatus;
import javax.net.p2p.rpc.proto.RpcStatusCode;

/**
 * 基于共享发送器的服务端响应观察者。
 */
public final class RpcServerResponseObserver implements RpcServerStreamObserver<Message> {
    private final RpcQueuedFrameSender frameSender;
    private final RpcFrame requestFrame;
    private final RpcRequestContext requestContext;
    private boolean completed;

    public RpcServerResponseObserver(RpcQueuedFrameSender frameSender, RpcFrame requestFrame, RpcRequestContext requestContext) {
        this.frameSender = frameSender;
        this.requestFrame = requestFrame;
        this.requestContext = requestContext;
    }

    @Override
    public void onNext(Message response) throws Exception {
        if (completed) {
            return;
        }
        byte[] payload = response == null ? new byte[0] : response.toByteArray();
        frameSender.sendFrames(RpcFrames.chunkDataFrames(requestFrame, payload, frameSender.maxFrameBytes(), requestContext), false);
    }

    @Override
    public void onCompleted() throws Exception {
        if (completed) {
            return;
        }
        completed = true;
        RpcStatus okStatus = RpcFrames.complete(requestFrame, new byte[0], null, true, requestContext).getStatus();
        afterComplete(okStatus);
        RpcFrame closeFrame = RpcFrames.complete(requestFrame, new byte[0], null, true, requestContext).toBuilder()
            .setFrameType(RpcFrameType.CLOSE)
            .build();
        frameSender.send(closeFrame, true);
    }

    @Override
    public void onError(Exception exception) throws Exception {
        if (completed) {
            return;
        }
        completed = true;
        afterError(RpcStatusCode.INTERNAL_ERROR, exception == null ? "" : exception.getMessage());
        frameSender.send(
            RpcFrames.error(requestFrame, RpcStatusCode.INTERNAL_ERROR, exception == null ? "" : exception.getMessage(), false, requestContext),
            true
        );
    }

    private void afterComplete(javax.net.p2p.rpc.proto.RpcStatus status) {
        for (RpcServerInterceptor interceptor : RpcServerInterceptors.all()) {
            interceptor.afterComplete(requestContext, status);
        }
    }

    private void afterError(RpcStatusCode code, String message) {
        for (RpcServerInterceptor interceptor : RpcServerInterceptors.all()) {
            interceptor.afterError(requestContext, code, message);
        }
    }
}
