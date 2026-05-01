package javax.net.p2p.rpc.server;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.p2p.rpc.pubsub.proto.PubSubPublishRequest;
import javax.net.p2p.rpc.pubsub.proto.PubSubPublishResponse;
import javax.net.p2p.rpc.pubsub.proto.PubSubSubscribeRequest;
import javax.net.p2p.rpc.pubsub.proto.PubSubEvent;

/**
 * RPC PubSub 内置服务。
 */
public final class RpcPubSubServices {
    public static final String SERVICE = "p2p.rpc.pubsub.v1.PubSubService";
    public static final String METHOD_PUBLISH = "Publish";
    public static final String METHOD_SUBSCRIBE = "Subscribe";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private RpcPubSubServices() {
    }

    public static void registerDefaults() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        RpcBootstrap.registerUnary(
            SERVICE,
            METHOD_PUBLISH,
            "v1",
            false,
            PubSubPublishRequest.class,
            PubSubPublishResponse.class,
            (context, request) -> {
                boolean accepted = RpcPubSubBroker.isTopicAllowed(request.getTopic());
                int subscribers = accepted ? RpcPubSubBroker.publish(request.getTopic(), request.getMessage()) : 0;
                return PubSubPublishResponse.newBuilder()
                    .setAccepted(accepted)
                    .setSubscriberCount(subscribers)
                    .build();
            }
        );
        RpcBootstrap.registerServerStream(
            SERVICE,
            METHOD_SUBSCRIBE,
            "v1",
            true,
            PubSubSubscribeRequest.class,
            PubSubEvent.class,
            (context, request, observer) -> {
                // 订阅由 RPC_EVENT 处理器接管，这里仅为 discover/元数据注册占位。
                throw new UnsupportedOperationException("Subscribe is handled by RPC_EVENT");
            }
        );
    }
}
