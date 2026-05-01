package javax.net.p2p.rpc.api;

import com.google.protobuf.Message;

/**
 * 服务端处理 bidi-stream 的单次会话。
 *
 * @param <Req> 请求元素类型
 * @param <Resp> 响应元素类型
 */
public interface RpcBidiStreamSession<Req extends Message, Resp extends Message> {

    void onNext(Req request) throws Exception;

    void onCompleted() throws Exception;

    default void onCancel() throws Exception {
    }
}
