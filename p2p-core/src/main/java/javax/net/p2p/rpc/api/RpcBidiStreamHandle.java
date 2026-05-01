package javax.net.p2p.rpc.api;

import com.google.protobuf.Message;

/**
 * 双向流客户端句柄。
 *
 * @param <Req> 请求消息类型
 */
public interface RpcBidiStreamHandle<Req extends Message> extends AutoCloseable {

    int requestId();

    void send(Req request) throws Exception;

    void halfClose() throws Exception;

    void cancel() throws Exception;

    @Override
    default void close() throws Exception {
        cancel();
    }
}
