package javax.net.p2p.rpc.api;

import javax.net.p2p.rpc.model.RpcRequestContext;
import javax.net.p2p.rpc.proto.RpcStatus;
import javax.net.p2p.rpc.proto.RpcStatusCode;

/**
 * RPC 服务端轻量拦截器，统一承载审计、鉴权和治理。
 */
public interface RpcServerInterceptor {

    default RpcStatus beforeHandle(RpcRequestContext context) {
        return null;
    }

    default void afterComplete(RpcRequestContext context, RpcStatus status) {
    }

    default void afterError(RpcRequestContext context, RpcStatusCode code, String message) {
    }
}
