package p2pws.sdk.core_compat;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.rpc.proto.RpcCallType;
import javax.net.p2p.rpc.proto.RpcFlowControl;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.proto.RpcMeta;

public final class CoreRpcStreamClient {
    private final CoreWsClient ws;

    public CoreRpcStreamClient(CoreWsClient ws) {
        this.ws = Objects.requireNonNull(ws, "ws");
    }

    public <Resp extends Message> CompletableFuture<CoreRpcStreamHandle> openServerStream(
        String service,
        String method,
        String version,
        byte[] requestPayload,
        Class<Resp> responseType,
        int initialPermits,
        int windowUpdateBatch,
        int maxInflightFrames,
        int maxFrameBytes,
        CoreRpcStreamObserver<Resp> observer,
        Duration openTimeout
    ) {
        if (service == null || service.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("service required"));
        }
        if (method == null || method.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("method required"));
        }
        String v = version == null ? "v1" : version;
        int requestId = ws.allocateSeq();

        Consumer<RpcFrame> controlSender = frame -> {
            byte[] bytes = frame.toByteArray();
            ws.sendObject(P2PWrapper.build(requestId, P2PCommand.RPC_CONTROL, bytes), true);
        };
        CoreRpcServerStreamSession<Resp> session = new CoreRpcServerStreamSession<>(
            requestId,
            P2PCommand.RPC_STREAM,
            responseType,
            observer,
            controlSender,
            windowUpdateBatch
        );
        ws.registerStreamHandler(requestId, session);

        RpcMeta meta = RpcMeta.newBuilder()
            .setService(service)
            .setMethod(method)
            .setServiceVersion(v)
            .setCallType(RpcCallType.SERVER_STREAM)
            .setRequestId(requestId)
            .build();
        RpcFrame open = RpcFrame.newBuilder()
            .setFrameType(RpcFrameType.OPEN)
            .setMeta(meta)
            .setPayload(ByteString.copyFrom(requestPayload == null ? new byte[0] : requestPayload))
            .setFlowControl(RpcFlowControl.newBuilder()
                .setPermits(initialPermits <= 0 ? 2 : initialPermits)
                .setMaxInflightFrames(Math.max(0, maxInflightFrames))
                .setMaxFrameBytes(Math.max(0, maxFrameBytes))
                .build())
            .setEndOfStream(true)
            .build();

        StreamP2PWrapper openWrapper = StreamP2PWrapper.buildStream(
            requestId,
            0,
            P2PCommand.RPC_STREAM,
            open.toByteArray(),
            false
        );
        return ws.sendStreamOpen(openWrapper, openTimeout).thenApply(ack -> {
            if (ack.getCommand() != P2PCommand.STREAM_ACK) {
                ws.registerStreamHandler(requestId, null);
                throw new IllegalStateException("open stream failed: " + ack.getCommand());
            }
            Runnable cancel = () -> {
                session.close();
                RpcFrame cancelFrame = RpcFrame.newBuilder()
                    .setFrameType(RpcFrameType.CANCEL)
                    .setMeta(RpcMeta.newBuilder().setRequestId(requestId).build())
                    .setEndOfStream(true)
                    .build();
                ws.sendObject(P2PWrapper.build(requestId, P2PCommand.RPC_CONTROL, cancelFrame.toByteArray()), true);
            };
            Runnable close = () -> {
                session.close();
                ws.registerStreamHandler(requestId, null);
            };
            return new CoreRpcStreamHandle(cancel, close);
        });
    }
}
