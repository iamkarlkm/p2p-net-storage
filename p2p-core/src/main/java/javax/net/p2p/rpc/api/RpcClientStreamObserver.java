package javax.net.p2p.rpc.api;

import com.google.protobuf.Message;

/**
 * 客户端流回调接口。
 *
 * @param <Resp> 流元素类型
 */
public interface RpcClientStreamObserver<Resp extends Message> {

    /**
     * 响应帧到达时的元数据回调；默认保持兼容。
     */
    default void onResponseContext(RpcClientResponseContext context) throws Exception {
    }

    void onNext(Resp response) throws Exception;

    void onCompleted() throws Exception;

    void onError(Exception exception) throws Exception;
}
