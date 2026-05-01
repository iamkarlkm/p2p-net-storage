package javax.net.p2p.rpc.server;

import com.google.protobuf.Message;
import javax.net.p2p.rpc.api.RpcServerStreamObserver;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.proto.RpcStatusCode;

/**
 * 基于共享发送器的服务端响应观察者。
 */
public final class RpcServerResponseObserver implements RpcServerStreamObserver<Message> {
    private final RpcQueuedFrameSender frameSender;
    private final RpcFrame requestFrame;
    private boolean completed;

    public RpcServerResponseObserver(RpcQueuedFrameSender frameSender, RpcFrame requestFrame) {
        this.frameSender = frameSender;
        this.requestFrame = requestFrame;
    }

    @Override
    public void onNext(Message response) throws Exception {
        if (completed) {
            return;
        }
        byte[] payload = response == null ? new byte[0] : response.toByteArray();
        frameSender.sendChunkedPayload(requestFrame, payload);
    }

    @Override
    public void onCompleted() throws Exception {
        if (completed) {
            return;
        }
        completed = true;
        frameSender.send(RpcFrame.newBuilder()
            .setMeta(requestFrame.getMeta())
            .setFrameType(RpcFrameType.CLOSE)
            .setStatus(javax.net.p2p.rpc.proto.RpcStatus.newBuilder()
                .setCode(RpcStatusCode.OK)
                .setRetriable(false)
                .build())
            .setEndOfStream(true)
            .build(), true);
    }

    @Override
    public void onError(Exception exception) throws Exception {
        if (completed) {
            return;
        }
        completed = true;
        frameSender.send(
            RpcFrames.error(requestFrame, RpcStatusCode.INTERNAL_ERROR, exception == null ? "" : exception.getMessage(), false),
            true
        );
    }
}
