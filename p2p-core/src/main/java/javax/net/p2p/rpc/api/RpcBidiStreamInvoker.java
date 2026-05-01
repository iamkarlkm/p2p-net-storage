package javax.net.p2p.rpc.api;

import com.google.protobuf.Message;
import javax.net.p2p.rpc.model.RpcRequestContext;

/**
 * bidi-stream 方法会在每次打开新流时创建一个独立会话。
 *
 * @param <Req> 请求元素类型
 * @param <Resp> 响应元素类型
 */
public interface RpcBidiStreamInvoker<Req extends Message, Resp extends Message> {

    RpcBidiStreamSession<Req, Resp> open(
        RpcRequestContext context,
        RpcServerStreamObserver<Resp> observer
    ) throws Exception;
}
