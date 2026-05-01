package javax.net.p2p.rpc.api;

import com.google.protobuf.Message;
import javax.net.p2p.rpc.proto.RpcStatus;

/**
 * 根据业务响应生成 RPC 状态，便于把领域码映射到统一治理状态。
 */
@FunctionalInterface
public interface RpcResponseStatusResolver<Resp extends Message> {

    RpcStatus resolve(Resp response);
}
