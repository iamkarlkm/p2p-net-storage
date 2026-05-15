package p2pws.sdk.core_compat;

import java.util.Map;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.proto.RpcMeta;
import javax.net.p2p.rpc.proto.RpcStatus;

public record CoreRpcResponseContext(
    RpcMeta meta,
    RpcStatus status,
    RpcFrameType frameType,
    boolean endOfStream,
    Map<String, String> responseHeaders,
    Map<String, String> responseTrailers
) {
}

