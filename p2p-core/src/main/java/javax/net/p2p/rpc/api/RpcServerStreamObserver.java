package javax.net.p2p.rpc.api;

import com.google.protobuf.Message;

/**
 * 服务端流输出观察者。
 *
 * @param <Resp> 流元素类型
 */
public interface RpcServerStreamObserver<Resp extends Message> {

    void onNext(Resp response) throws Exception;

    void onCompleted() throws Exception;

    void onError(Exception exception) throws Exception;
}
