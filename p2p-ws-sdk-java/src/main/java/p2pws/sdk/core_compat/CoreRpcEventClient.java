package p2pws.sdk.core_compat;

import com.google.protobuf.ByteString;
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
import javax.net.p2p.rpc.pubsub.proto.PubSubEvent;
import javax.net.p2p.rpc.pubsub.proto.PubSubPublishRequest;
import javax.net.p2p.rpc.pubsub.proto.PubSubPublishResponse;
import javax.net.p2p.rpc.pubsub.proto.PubSubSubscribeRequest;

public final class CoreRpcEventClient {
    public static final String PUBSUB_SERVICE = "p2p.rpc.pubsub.v1.PubSubService";
    public static final String PUBSUB_PUBLISH = "Publish";
    public static final String PUBSUB_SUBSCRIBE = "Subscribe";

    private final CoreWsClient ws;

    public CoreRpcEventClient(CoreWsClient ws) {
        this.ws = Objects.requireNonNull(ws, "ws");
    }

    public CompletableFuture<CoreRpcEventSubscription> subscribe(
        String topic,
        int initialPermits,
        int windowUpdateBatch,
        int maxInflightFrames,
        int maxFrameBytes,
        CoreRpcStreamObserver<PubSubEvent> observer,
        Duration openTimeout
    ) {
        if (topic == null || topic.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("topic required"));
        }
        int requestId = ws.allocateSeq();

        Consumer<RpcFrame> controlSender = frame -> ws.sendObject(
            P2PWrapper.build(requestId, P2PCommand.RPC_CONTROL, frame.toByteArray()),
            true
        );
        CoreRpcServerStreamSession<PubSubEvent> session = new CoreRpcServerStreamSession<>(
            requestId,
            P2PCommand.RPC_EVENT,
            PubSubEvent.class,
            observer,
            controlSender,
            windowUpdateBatch
        );
        ws.registerStreamHandler(requestId, session);

        byte[] subscribeBytes = PubSubSubscribeRequest.newBuilder().setTopic(topic).build().toByteArray();
        RpcMeta meta = RpcMeta.newBuilder()
            .setService(PUBSUB_SERVICE)
            .setMethod(PUBSUB_SUBSCRIBE)
            .setServiceVersion("v1")
            .setCallType(RpcCallType.SERVER_STREAM)
            .setRequestId(requestId)
            .build();
        RpcFrame open = RpcFrame.newBuilder()
            .setFrameType(RpcFrameType.OPEN)
            .setMeta(meta)
            .setPayload(ByteString.copyFrom(subscribeBytes))
            .setFlowControl(RpcFlowControl.newBuilder()
                .setPermits(initialPermits <= 0 ? 2 : initialPermits)
                .setMaxInflightFrames(Math.max(0, maxInflightFrames))
                .setMaxFrameBytes(Math.max(0, maxFrameBytes))
                .build())
            .setEndOfStream(false)
            .build();

        StreamP2PWrapper openWrapper = StreamP2PWrapper.buildStream(
            requestId,
            0,
            P2PCommand.RPC_EVENT,
            open.toByteArray(),
            false
        );
        return ws.sendStreamOpen(openWrapper, openTimeout).thenApply(ack -> {
            if (ack.getCommand() != P2PCommand.STREAM_ACK) {
                ws.registerStreamHandler(requestId, null);
                throw new IllegalStateException("open event stream failed: " + ack.getCommand());
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
            return new CoreRpcEventSubscription(requestId, cancel, close);
        });
    }

    public CompletableFuture<PubSubPublishResponse> publish(String topic, String message, Duration timeout) {
        if (topic == null || topic.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("topic required"));
        }
        int requestId = ws.allocateSeq();
        byte[] publishBytes = PubSubPublishRequest.newBuilder()
            .setTopic(topic)
            .setMessage(message == null ? "" : message)
            .build()
            .toByteArray();

        RpcMeta meta = RpcMeta.newBuilder()
            .setService(PUBSUB_SERVICE)
            .setMethod(PUBSUB_PUBLISH)
            .setServiceVersion("v1")
            .setCallType(RpcCallType.UNARY)
            .setRequestId(requestId)
            .build();
        RpcFrame open = RpcFrame.newBuilder()
            .setFrameType(RpcFrameType.OPEN)
            .setMeta(meta)
            .setPayload(ByteString.copyFrom(publishBytes))
            .setEndOfStream(true)
            .build();

        return ws.sendAndAwait(P2PWrapper.build(requestId, P2PCommand.RPC_UNARY, open.toByteArray()), true, timeout)
            .thenApply(resp -> {
                if (resp.getCommand() == P2PCommand.STD_ERROR) {
                    throw new IllegalStateException(String.valueOf(resp.getData()));
                }
                if (resp.getCommand() != P2PCommand.RPC_UNARY) {
                    throw new IllegalStateException("publish failed: " + resp.getCommand());
                }
                Object data = resp.getData();
                if (!(data instanceof byte[] bytes)) {
                    throw new IllegalStateException("publish invalid response type");
                }
                try {
                    RpcFrame responseFrame = RpcFrame.parseFrom(bytes);
                    if (!responseFrame.hasStatus() || responseFrame.getStatus().getCode() != javax.net.p2p.rpc.proto.RpcStatusCode.OK) {
                        throw new IllegalStateException(responseFrame.getStatus().getMessage());
                    }
                    return PubSubPublishResponse.parseFrom(responseFrame.getPayload());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
    }
}
