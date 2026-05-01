package javax.net.p2p.rpc.api;

/**
 * 客户端流订阅句柄。
 */
public interface RpcStreamSubscription extends AutoCloseable {

    int requestId();

    void cancel();

    @Override
    default void close() {
        cancel();
    }
}
