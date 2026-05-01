package javax.net.p2p.rpc.api;

import com.google.protobuf.Message;
import javax.net.p2p.rpc.model.RpcRequestContext;

/**
 * RPC 方法执行器，隔离协议分发与业务实现。
 *
 * @param <Req> 请求类型
 * @param <Resp> 响应类型
 */
public interface RpcMethodInvoker<Req extends Message, Resp extends Message> {

    Resp invoke(RpcRequestContext context, Req request) throws Exception;
}
