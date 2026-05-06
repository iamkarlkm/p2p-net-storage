package javax.net.p2p.rpc.api;

import java.util.Map;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.proto.RpcMeta;
import javax.net.p2p.rpc.proto.RpcStatus;

/**
 * 客户端可观察的 RPC 响应上下文。
 */
public record RpcClientResponseContext(
    RpcMeta meta,
    RpcStatus status,
    RpcFrameType frameType,
    boolean endOfStream,
    Map<String, String> responseHeaders,
    Map<String, String> responseTrailers
) {
}
