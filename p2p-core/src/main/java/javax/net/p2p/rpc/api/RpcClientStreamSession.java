package javax.net.p2p.rpc.api;

import com.google.protobuf.Message;

/**
 * 服务端处理 client-stream 的单次会话。
 *
 * @param <Req> 请求元素类型
 * @param <Resp> 完成时返回的聚合响应类型
 */
public interface RpcClientStreamSession<Req extends Message, Resp extends Message> {

    void onNext(Req request) throws Exception;

    Resp onCompleted() throws Exception;

    default void onCancel() throws Exception {
    }
}
