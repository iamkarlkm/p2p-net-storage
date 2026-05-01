package javax.net.p2p.rpc.api;

import com.google.protobuf.Message;
import javax.net.p2p.rpc.model.RpcRequestContext;

/**
 * 服务端流方法执行器。
 *
 * @param <Req> 请求类型
 * @param <Resp> 响应流元素类型
 */
public interface RpcServerStreamInvoker<Req extends Message, Resp extends Message> {

    void invoke(RpcRequestContext context, Req request, RpcServerStreamObserver<Resp> observer) throws Exception;
}
