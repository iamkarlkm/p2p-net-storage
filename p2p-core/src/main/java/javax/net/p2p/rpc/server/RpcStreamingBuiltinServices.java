package javax.net.p2p.rpc.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.p2p.rpc.stream.proto.StreamChatRequest;
import javax.net.p2p.rpc.stream.proto.StreamChatResponse;
import javax.net.p2p.rpc.stream.proto.StreamCollectRequest;
import javax.net.p2p.rpc.stream.proto.StreamCollectResponse;

/**
 * 内置的 client-stream / bidi-stream 示例服务。
 */
public final class RpcStreamingBuiltinServices {
    public static final String SERVICE = "p2p.rpc.stream.v1.StreamService";
    public static final String METHOD_COLLECT = "Collect";
    public static final String METHOD_CHAT = "Chat";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private RpcStreamingBuiltinServices() {
    }

    public static void registerDefaults() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        RpcBootstrap.registerClientStream(
            SERVICE,
            METHOD_COLLECT,
            "v1",
            false,
            StreamCollectRequest.class,
            StreamCollectResponse.class,
            context -> new CollectSession(),
            response -> javax.net.p2p.rpc.proto.RpcStatus.newBuilder()
                .setCode(javax.net.p2p.rpc.proto.RpcStatusCode.OK)
                .setRetriable(false)
                .build()
        );
        RpcBootstrap.registerBidiStream(
            SERVICE,
            METHOD_CHAT,
            "v1",
            false,
            StreamChatRequest.class,
            StreamChatResponse.class,
            (context, observer) -> new ChatSession(observer)
        );
    }

    private static final class CollectSession implements javax.net.p2p.rpc.api.RpcClientStreamSession<StreamCollectRequest, StreamCollectResponse> {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void onNext(StreamCollectRequest request) {
            messages.add(request == null ? "" : request.getMessage());
        }

        @Override
        public StreamCollectResponse onCompleted() {
            return StreamCollectResponse.newBuilder()
                .addAllMessages(messages)
                .setCount(messages.size())
                .setJoined(String.join(",", messages))
                .build();
        }
    }

    private static final class ChatSession implements javax.net.p2p.rpc.api.RpcBidiStreamSession<StreamChatRequest, StreamChatResponse> {
        private final javax.net.p2p.rpc.api.RpcServerStreamObserver<StreamChatResponse> observer;
        private final AtomicInteger index = new AtomicInteger(1);

        private ChatSession(javax.net.p2p.rpc.api.RpcServerStreamObserver<StreamChatResponse> observer) {
            this.observer = observer;
        }

        @Override
        public void onNext(StreamChatRequest request) throws Exception {
            observer.onNext(StreamChatResponse.newBuilder()
                .setIndex(index.getAndIncrement())
                .setMessage("ack:" + (request == null ? "" : request.getMessage()))
                .build());
        }

        @Override
        public void onCompleted() throws Exception {
            observer.onCompleted();
        }
    }
}
