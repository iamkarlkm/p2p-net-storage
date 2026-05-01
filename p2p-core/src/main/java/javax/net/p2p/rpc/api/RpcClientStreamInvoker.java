package javax.net.p2p.rpc.api;

import com.google.protobuf.Message;
import javax.net.p2p.rpc.model.RpcRequestContext;

/**
 * client-stream 方法会在每次打开新流时创建一个独立会话。
 *
 * @param <Req> 请求元素类型
 * @param <Resp> 聚合响应类型
 */
public interface RpcClientStreamInvoker<Req extends Message, Resp extends Message> {

    RpcClientStreamSession<Req, Resp> open(RpcRequestContext context) throws Exception;
}
