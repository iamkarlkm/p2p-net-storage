package javax.net.p2p.rpc.api;

import com.google.protobuf.Message;

/**
 * unary 调用的详细返回结果。
 */
public record RpcUnaryResult<Resp extends Message>(
    Resp response,
    RpcClientResponseContext context
) {
}
