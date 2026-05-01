package javax.net.p2p.rpc.api;

import com.google.protobuf.Message;

/**
 * 客户端主动发送的请求流句柄。
 *
 * @param <Req> 请求消息类型
 * @param <Resp> 聚合响应类型
 */
public interface RpcClientStreamHandle<Req extends Message, Resp extends Message> extends AutoCloseable {

    int requestId();

    void send(Req request) throws Exception;

    Resp halfCloseAndAwait() throws Exception;

    void cancel() throws Exception;

    @Override
    default void close() throws Exception {
        cancel();
    }
}
